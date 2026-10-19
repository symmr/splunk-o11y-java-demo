package com.example.shop.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return catalog.categories();
    }

    @GetMapping("/products")
    public List<Product> products(@RequestParam(defaultValue = "books") String category) {
        return catalog.listByCategory(category);
    }

    @GetMapping("/products/{id}")
    public CatalogService.PricedProduct product(@PathVariable long id) {
        return catalog.detail(id);
    }

    @GetMapping("/search")
    public List<CatalogService.SearchHit> search(@RequestParam("q") String q) {
        return catalog.search(q);
    }

    @GetMapping("/recommendations")
    public List<Product> recommendations(@RequestParam(required = false) Long customerId) {
        return catalog.recommendations(customerId != null ? customerId : catalog.randomCustomerId());
    }
}
