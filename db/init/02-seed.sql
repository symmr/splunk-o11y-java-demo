-- Deterministic-ish seed data: 5k products, 20k customers, 300k orders, ~1M order items.

SELECT setseed(0.42);

INSERT INTO products (id, sku, name, category, description, price)
SELECT g,
       'SKU-' || lpad(g::text, 6, '0'),
       (ARRAY['Classic','Smart','Eco','Pro','Mini','Ultra','Vintage','Urban'])[1 + g % 8] || ' ' ||
       (ARRAY['Lamp','Chair','Mug','Backpack','Novel','Headphones','Kettle','Sneakers','Notebook','Camera'])[1 + (g / 8) % 10] || ' ' || g,
       (ARRAY['books','electronics','home','fashion','outdoor','kitchen','toys','sports'])[1 + g % 8],
       'A ' || (ARRAY['durable','lightweight','handmade','wireless','organic','compact','premium','budget'])[1 + (g / 3) % 8] ||
       ' item for ' || (ARRAY['travel','office','camping','cooking','gaming','reading','running','gifting'])[1 + (g / 5) % 8] ||
       '. ' || repeat('Lorem ipsum dolor sit amet. ', 4),
       round((5 + random() * 495)::numeric, 2)
FROM generate_series(1, 5000) g;

INSERT INTO inventory (product_id, stock)
SELECT id, 100000000 FROM products;

INSERT INTO customers (id, name, tier)
SELECT g, 'customer-' || g, (ARRAY['bronze','bronze','bronze','silver','silver','gold'])[1 + g % 6]
FROM generate_series(1, 20000) g;

INSERT INTO orders (id, customer_id, status, total, created_at)
SELECT g, 1 + (random() * 19999)::int, 'PAID', 0, now() - (random() * interval '365 days')
FROM generate_series(1, 300000) g;
SELECT setval('orders_id_seq', 300000);

INSERT INTO order_items (order_id, product_id, quantity, unit_price)
SELECT 1 + (g % 300000), 1 + (random() * 4999)::int, 1 + (random() * 3)::int, round((5 + random() * 495)::numeric, 2)
FROM generate_series(1, 1000000) g;

UPDATE orders o SET total = s.total
FROM (SELECT order_id, SUM(quantity * unit_price) AS total FROM order_items GROUP BY order_id) s
WHERE o.id = s.order_id;

UPDATE products p SET sold_count = s.units
FROM (SELECT product_id, SUM(quantity) AS units FROM order_items GROUP BY product_id) s
WHERE p.id = s.product_id;

ANALYZE;
