package com.example.shop.catalog;

import java.math.BigDecimal;

public record Product(long id, String sku, String name, String category, String description,
                      BigDecimal price, int soldCount) {}
