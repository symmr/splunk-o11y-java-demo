package com.example.shop.chaos;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/**
 * In-memory switchboard for the failure scenarios. State is lost on restart on purpose:
 * after a memory-leak OOM the app comes back healthy.
 */
@Component
public class ChaosState {

    public record Setting(boolean enabled, int intensity) {}

    private final Map<Scenario, Setting> settings = new EnumMap<>(Scenario.class);
    private final Map<Scenario, Consumer<Setting>> listeners = new EnumMap<>(Scenario.class);

    public ChaosState() {
        for (Scenario s : Scenario.values()) {
            settings.put(s, new Setting(false, s.defaultIntensity()));
        }
    }

    public synchronized Setting get(Scenario s) {
        return settings.get(s);
    }

    public synchronized Map<Scenario, Setting> snapshot() {
        return new EnumMap<>(settings);
    }

    public void set(Scenario s, boolean enabled, Integer intensity) {
        Setting updated;
        Consumer<Setting> listener;
        synchronized (this) {
            int i = intensity == null ? settings.get(s).intensity() : Math.max(1, Math.min(100, intensity));
            updated = new Setting(enabled, i);
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
}
