package com.example.shop.catalog;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.example.shop.chaos.ChaosState;
import com.example.shop.chaos.Scenario;

/**
 * A cache keyed by a per-request id, so nothing is ever hit or evicted: a classic leak.
 * Heap grows steadily until the JVM dies with OutOfMemoryError (-XX:+ExitOnOutOfMemoryError)
 * and Docker restarts the container.
 */
@Component
public class PersonalizationCache {

    record Profile(long productId, byte[] features) {}

    private final Map<String, Profile> cache = new ConcurrentHashMap<>();
    private final ChaosState chaos;

    public PersonalizationCache(ChaosState chaos) {
        this.chaos = chaos;
        chaos.onChange(Scenario.MEMORY_LEAK, s -> {
            if (!s.enabled()) {
                cache.clear();
            }
        });
    }

    public void remember(long productId) {
        if (!chaos.enabled(Scenario.MEMORY_LEAK)) {
            return;
        }
        int bytes = chaos.intensity(Scenario.MEMORY_LEAK) * 2 * 1024;
        cache.put(UUID.randomUUID().toString(), new Profile(productId, new byte[bytes]));
    }

    public int size() {
        return cache.size();
    }
}
