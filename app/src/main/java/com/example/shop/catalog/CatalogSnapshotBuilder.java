package com.example.shop.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.shop.chaos.ChaosState;
import com.example.shop.chaos.Scenario;

/**
 * Builds a "rendering snapshot" of a product listing. With gc-pressure enabled it renders
 * many throw-away copies and keeps the most recent ones in a window, so objects survive
 * long enough to be promoted to the old generation and the collector keeps running.
 */
@Component
public class CatalogSnapshotBuilder {

    private static final int CHUNK_BYTES = 64 * 1024;
    /** ~96MB of recent snapshots kept alive against a 256MB heap. */
    private static final int RETAINED_CHUNKS = 1_536;

    private final ChaosState chaos;
    private final byte[][] recent = new byte[RETAINED_CHUNKS][];
    private int cursor;

    public CatalogSnapshotBuilder(ChaosState chaos) {
        this.chaos = chaos;
        chaos.onChange(Scenario.GC_PRESSURE, s -> {
            if (!s.enabled()) {
                clear();
            }
        });
    }

    public int build(List<Product> products) {
        if (!chaos.enabled(Scenario.GC_PRESSURE)) {
            return products.size();
        }
        long bytesToAllocate = chaos.intensity(Scenario.GC_PRESSURE) * 512L * 1024;
        List<byte[]> rendered = new ArrayList<>();
        StringBuilder template = new StringBuilder();
        for (Product p : products) {
            template.append(p.sku()).append(';').append(p.name()).append(';').append(p.price()).append('\n');
        }
        byte[] row = template.toString().getBytes(StandardCharsets.UTF_8);
        for (long allocated = 0; allocated < bytesToAllocate; allocated += CHUNK_BYTES) {
            byte[] chunk = new byte[CHUNK_BYTES];
            for (int i = 0; i < chunk.length; i += Math.max(1, row.length)) {
                System.arraycopy(row, 0, chunk, i, Math.min(row.length, chunk.length - i));
            }
            rendered.add(chunk);
        }
        retain(rendered);
        return products.size();
    }

    private synchronized void retain(List<byte[]> chunks) {
        for (byte[] c : chunks) {
            recent[cursor] = c;
            cursor = (cursor + 1) % RETAINED_CHUNKS;
        }
    }

    private synchronized void clear() {
        java.util.Arrays.fill(recent, null);
    }
}
