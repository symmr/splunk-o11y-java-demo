package com.example.shop.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.example.shop.chaos.ChaosState;
import com.example.shop.chaos.Scenario;

/**
 * Applies dynamic pricing. With cpu-hotspot enabled it runs a naive O(n²) "price trend" analysis
 * that shows up as the widest frame in the CPU flame graph.
 */
@Component
public class PriceCalculator {

    private final ChaosState chaos;

    public PriceCalculator(ChaosState chaos) {
        this.chaos = chaos;
    }

    public BigDecimal calculate(Product product) {
        double factor = 1.0;
        if (chaos.enabled(Scenario.CPU_HOTSPOT)) {
            int historySize = 1_000 + chaos.intensity(Scenario.CPU_HOTSPOT) * 40;
            factor = computeHistoricalPriceTrend(product.id(), historySize);
        }
        return product.price().multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Compares every historical price point with every other one. Intentionally quadratic. */
    double computeHistoricalPriceTrend(long seed, int historySize) {
        double[] history = new double[historySize];
        double p = 100 + seed % 50;
        for (int i = 0; i < historySize; i++) {
            p = p * (1 + Math.sin(seed + i) * 0.01);
            history[i] = p;
        }
        double ups = 0;
        double downs = 0;
        for (int i = 0; i < historySize; i++) {
            for (int j = i + 1; j < historySize; j++) {
                double delta = Math.log(history[j] / history[i]) / (j - i);
                if (delta > 0) {
                    ups += delta;
                } else {
                    downs -= delta;
                }
            }
        }
        double trend = (ups - downs) / (ups + downs + 1e-9);
        return 1.0 + Math.max(-0.05, Math.min(0.05, trend * 0.05));
    }
}
