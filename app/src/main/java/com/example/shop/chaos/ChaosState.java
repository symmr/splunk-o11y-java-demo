package com.example.shop.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory switchboard for the failure scenarios. State is lost on restart on purpose:
 * after a memory-leak OOM the app comes back healthy.
 *
 * Scenarios switch themselves off after {@code shop.chaos.auto-off-minutes} so a forgotten
 * scenario does not keep generating load, log volume and ingest cost. Explicitly switching a
 * scenario on (again) restarts its timer; changing only the intensity keeps it. Expiry is checked lazily on every read (the app is read
 * constantly by traffic and the sidecars), so no background thread or scheduled span is needed.
 */
@Component
public class ChaosState {

    private static final Logger log = LoggerFactory.getLogger(ChaosState.class);

    public record Setting(boolean enabled, int intensity, Instant expiresAt) {}

    private final Map<Scenario, Setting> settings = new EnumMap<>(Scenario.class);
    private final Map<Scenario, Consumer<Setting>> listeners = new EnumMap<>(Scenario.class);
    public static final long MAX_AUTO_OFF_MINUTES = 24 * 60;

    /** Applied when a scenario is switched on. Can be changed at runtime from the control page; null = never. */
    private volatile Duration autoOff;

    public ChaosState(@Value("${shop.chaos.auto-off-minutes:30}") long autoOffMinutes) {
        setAutoOffMinutes(autoOffMinutes);
        for (Scenario s : Scenario.values()) {
            settings.put(s, new Setting(false, s.defaultIntensity(), null));
        }
    }

    /** Minutes a scenario stays on after being switched on; 0 means it never switches off by itself. */
    public long autoOffMinutes() {
        Duration d = autoOff;
        return d == null ? 0 : d.toMinutes();
    }

    /** Takes effect for scenarios switched on afterwards; timers already running are not changed. */
    public void setAutoOffMinutes(long minutes) {
        long m = Math.max(0, Math.min(MAX_AUTO_OFF_MINUTES, minutes));
        this.autoOff = m > 0 ? Duration.ofMinutes(m) : null;
    }

    public Setting get(Scenario s) {
        expireDue();
        synchronized (this) {
            return settings.get(s);
        }
    }

    public Map<Scenario, Setting> snapshot() {
        expireDue();
        synchronized (this) {
            return new EnumMap<>(settings);
        }
    }

    public void set(Scenario s, boolean enabled, Integer intensity) {
        set(s, enabled, intensity, enabled);
    }

    public void set(Scenario s, boolean enabled, Integer intensity, boolean restartTimer) {
        Setting updated;
        Consumer<Setting> listener;
        synchronized (this) {
            Setting current = settings.get(s);
            int i = intensity == null ? current.intensity() : Math.max(1, Math.min(100, intensity));
            Duration autoOffNow = autoOff;
            Instant expiresAt = !enabled || autoOffNow == null ? null
                    : restartTimer || current.expiresAt() == null ? Instant.now().plus(autoOffNow)
                    : current.expiresAt();
            updated = new Setting(enabled, i, expiresAt);
            settings.put(s, updated);
            listener = listeners.get(s);
        }
        if (listener != null) {
            listener.accept(updated);
        }
    }

    public void reset() {
        for (Scenario s : Scenario.values()) {
            set(s, false, s.defaultIntensity());
        }
    }

    /** Called whenever a scenario changes, e.g. to release leaked memory when it is switched off. */
    public synchronized void onChange(Scenario s, Consumer<Setting> listener) {
        listeners.put(s, listener);
    }

    public boolean enabled(Scenario s) {
        return get(s).enabled();
    }

    public int intensity(Scenario s) {
        return get(s).intensity();
    }

    /** True for roughly {@code percent}% of calls. */
    public static boolean chance(int percent) {
        return ThreadLocalRandom.current().nextInt(100) < percent;
    }

    private void expireDue() {
        List<Scenario> due = new ArrayList<>();
        Instant now = Instant.now();
        synchronized (this) {
            settings.forEach((s, setting) -> {
                if (setting.enabled() && setting.expiresAt() != null && now.isAfter(setting.expiresAt())) {
                    due.add(s);
                }
            });
        }
        for (Scenario s : due) {
            log.info("Chaos scenario {} switched off automatically (auto-off timer expired)", s.id());
            set(s, false, null);
        }
    }
}
