package com.example.shop.order;

import java.util.List;

public record OrderRequest(Long customerId, List<Item> items, String couponCode, String paymentMethod) {

    public record Item(long productId, int quantity) {}
}
