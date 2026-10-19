-- Schema for the demo shop.
-- NOTE: orders.customer_id and order_items.product_id are intentionally NOT indexed.
-- The slow-query and pool-exhaustion scenarios depend on it.

CREATE TABLE products (
    id          BIGINT PRIMARY KEY,
    sku         TEXT NOT NULL,
    name        TEXT NOT NULL,
    category    TEXT NOT NULL,
    description TEXT NOT NULL,
    price       NUMERIC(10, 2) NOT NULL,
    sold_count  INT NOT NULL DEFAULT 0
);
CREATE INDEX products_category_idx ON products (category, sold_count DESC);
CREATE INDEX products_name_prefix_idx ON products (lower(name) text_pattern_ops);

CREATE TABLE customers (
    id   BIGINT PRIMARY KEY,
    name TEXT NOT NULL,
    tier TEXT NOT NULL DEFAULT 'bronze'
);

CREATE TABLE inventory (
    product_id BIGINT PRIMARY KEY REFERENCES products (id),
    stock      INT NOT NULL
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    status      TEXT NOT NULL,
    total       NUMERIC(12, 2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   INT NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL
);
CREATE INDEX order_items_order_idx ON order_items (order_id);
