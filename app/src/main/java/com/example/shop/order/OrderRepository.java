package com.example.shop.order;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOrder(long customerId, BigDecimal total, String status) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO orders (customer_id, status, total, created_at) VALUES (?, ?, ?, now())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, customerId);
            ps.setString(2, status);
            ps.setBigDecimal(3, total);
            return ps;
        }, keys);
        return ((Number) keys.getKeys().get("id")).longValue();
    }

    public void insertItem(long orderId, long productId, int quantity, BigDecimal unitPrice) {
        jdbc.update("INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)",
                orderId, productId, quantity, unitPrice);
    }

    public BigDecimal priceOf(long productId) {
        return jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, productId);
    }

    /**
     * Ranks the customer against everyone else by lifetime spend. orders.customer_id has no
     * index and the subquery aggregates the whole table, so this takes hundreds of ms.
     */
    public long loyaltyRank(long customerId) {
        Long rank = jdbc.queryForObject("""
                SELECT COUNT(*) + 1 FROM (
                    SELECT customer_id, SUM(total) AS spend FROM orders GROUP BY customer_id
                ) t
                WHERE t.spend > (SELECT COALESCE(SUM(total), 0) FROM orders WHERE customer_id = ?)
                """, Long.class, customerId);
        return rank == null ? 0 : rank;
    }

    public void updateCustomerTier(long customerId, String tier) {
        jdbc.update("UPDATE customers SET tier = ? WHERE id = ?", tier, customerId);
    }

    public String customerTier(long customerId) {
        return jdbc.query("SELECT tier FROM customers WHERE id = ?", (rs, n) -> rs.getString(1), customerId)
                .stream().findFirst().orElse("unknown");
    }

    public Optional<Map<String, Object>> findOrder(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, customer_id, status, total, created_at FROM orders WHERE id = ?", id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> order = new java.util.LinkedHashMap<>(rows.get(0));
        order.put("items", jdbc.queryForList(
                "SELECT product_id, quantity, unit_price FROM order_items WHERE order_id = ?", id));
        return Optional.of(order);
    }
}
