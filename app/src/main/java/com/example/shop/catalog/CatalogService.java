package com.example.shop.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import com.example.shop.chaos.ChaosState;
import com.example.shop.chaos.Scenario;
import com.example.shop.web.NotFoundException;

@Service
public class CatalogService {

    public record PricedProduct(Product product, java.math.BigDecimal finalPrice) {}

    public record SearchHit(Product product, int unitsSold) {}

    private final ProductRepository products;
    private final PriceCalculator priceCalculator;
    private final CatalogSnapshotBuilder snapshotBuilder;
    private final PersonalizationCache personalization;
    private final ChaosState chaos;

    public CatalogService(ProductRepository products, PriceCalculator priceCalculator,
                          CatalogSnapshotBuilder snapshotBuilder, PersonalizationCache personalization,
                          ChaosState chaos) {
        this.products = products;
        this.priceCalculator = priceCalculator;
        this.snapshotBuilder = snapshotBuilder;
        this.personalization = personalization;
        this.chaos = chaos;
    }

    public List<Product> listByCategory(String category) {
        List<Product> result = products.findByCategory(category, 50);
        snapshotBuilder.build(result);
        return result;
    }

    public PricedProduct detail(long id) {
        Product p = products.findById(id).orElseThrow(() -> new NotFoundException("product " + id));
        personalization.remember(id);
        return new PricedProduct(p, priceCalculator.calculate(p));
    }

    public List<SearchHit> search(String term) {
        if (chaos.enabled(Scenario.SLOW_QUERY) && ChaosState.chance(chaos.intensity(Scenario.SLOW_QUERY))) {
            // "Improved" search: substring match and live popularity per hit (N+1, no index).
            List<SearchHit> hits = new ArrayList<>();
            for (Product p : products.searchAnywhere(term, 20)) {
                hits.add(new SearchHit(p, products.countUnitsSold(p.id())));
            }
            return hits;
        }
        return products.searchByNamePrefix(term, 20).stream()
                .map(p -> new SearchHit(p, p.soldCount()))
                .toList();
    }

    public List<Product> recommendations(long customerId) {
        List<String> categories = products.categories();
        String category = categories.get((int) (customerId % categories.size()));
        List<Product> picks = products.findByCategory(category, 10);
        picks.forEach(p -> personalization.remember(p.id()));
        return picks;
    }

    public List<String> categories() {
        return products.categories();
    }

    public long randomCustomerId() {
        return ThreadLocalRandom.current().nextLong(1, 20_001);
    }
}
