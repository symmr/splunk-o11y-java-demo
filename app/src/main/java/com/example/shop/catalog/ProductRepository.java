package com.example.shop.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {

    private static final String COLUMNS = "id, sku, name, category, description, price, sold_count";

    private static final RowMapper<Product> MAPPER = (rs, n) -> new Product(
            rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("category"),
            rs.getString("description"), rs.getBigDecimal("price"), rs.getInt("sold_count"));

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> findByCategory(String category, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM products WHERE category = ? ORDER BY sold_count DESC LIMIT ?",
                MAPPER, category, limit);
    }

    public Optional<Product> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM products WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** Uses the lower(name) text_pattern_ops index. */
    public List<Product> searchByNamePrefix(String term, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM products WHERE lower(name) LIKE ? ORDER BY sold_count DESC LIMIT ?",
                MAPPER, term.toLowerCase() + "%", limit);
    }

    /** Substring match on name and description: no index can help, always a sequential scan. */
    public List<Product> searchAnywhere(String term, int limit) {
        String pattern = "%" + term.toLowerCase() + "%";
        return jdbc.query("SELECT " + COLUMNS + " FROM products WHERE lower(name) LIKE ? OR lower(description) LIKE ? LIMIT ?",
                MAPPER, pattern, pattern, limit);
    }

    /** order_items.product_id has no index on purpose, so every call scans the whole table. */
    public int countUnitsSold(long productId) {
        Integer n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE product_id = ?", Integer.class, productId);
        return n == null ? 0 : n;
    }

    public List<String> categories() {
        return jdbc.queryForList("SELECT DISTINCT category FROM products ORDER BY category", String.class);
    }
}
