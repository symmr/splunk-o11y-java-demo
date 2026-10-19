package com.example.shop.order;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public OrderService.OrderResult checkout(@RequestBody OrderRequest request) {
        return orders.checkout(request);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return orders.find(id);
    }
}
