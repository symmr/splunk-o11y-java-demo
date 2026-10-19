package com.example.shop.order;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.shop.chaos.ChaosState;
import com.example.shop.chaos.Scenario;

@Service
public class InventoryService {

    /** Guards the (simulated) legacy warehouse system, which is not thread-safe. */
    private static final Object WAREHOUSE_LOCK = new Object();

    private final JdbcTemplate jdbc;
    private final ChaosState chaos;

    public InventoryService(JdbcTemplate jdbc, ChaosState chaos) {
        this.jdbc = jdbc;
        this.chaos = chaos;
    }

    public void reserve(List<OrderRequest.Item> items) {
        if (chaos.enabled(Scenario.LOCK_CONTENTION)) {
            synchronized (WAREHOUSE_LOCK) {
                syncWithLegacyWarehouse(items);
            }
        }
        for (OrderRequest.Item item : items) {
            jdbc.update("UPDATE inventory SET stock = stock - ? WHERE product_id = ?", item.quantity(), item.productId());
        }
    }

    /** Every order waits here in turn: low CPU, high latency, threads BLOCKED on WAREHOUSE_LOCK. */
    private void syncWithLegacyWarehouse(List<OrderRequest.Item> items) {
        try {
            Thread.sleep(chaos.intensity(Scenario.LOCK_CONTENTION) * 4L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
