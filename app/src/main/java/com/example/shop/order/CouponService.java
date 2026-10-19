package com.example.shop.order;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.shop.chaos.ChaosState;
import com.example.shop.chaos.Scenario;

import io.opentelemetry.api.trace.Span;

/**
 * Coupons look like "SPRING-10" (10% off). Legacy coupons ("LEGACY:xxxx") go through an old
 * parser that, with log-noise enabled, blows up and fails the order.
 */
@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    private final ChaosState chaos;

    public CouponService(ChaosState chaos) {
        this.chaos = chaos;
    }

    public BigDecimal discountRate(String code) {
        if (code == null || code.isBlank()) {
            return BigDecimal.ZERO;
        }
        boolean legacy = code.startsWith("LEGACY:");
        Span.current().setAttribute("coupon.type", legacy ? "legacy" : "standard");
        if (legacy) {
            return legacyDiscountRate(code);
        }
        int dash = code.lastIndexOf('-');
        int percent = dash < 0 ? 0 : Integer.parseInt(code.substring(dash + 1));
        return BigDecimal.valueOf(percent, 2);
    }

    private BigDecimal legacyDiscountRate(String code) {
        if (chaos.enabled(Scenario.LOG_NOISE) && ChaosState.chance(chaos.intensity(Scenario.LOG_NOISE))) {
            try {
                parseLegacyCampaign(code);
            } catch (RuntimeException e) {
                log.error("Failed to resolve legacy coupon {}; campaign table is out of sync", code, e);
                throw new CouponException("legacy coupon could not be applied: " + code, e);
            }
        }
        return BigDecimal.valueOf(5, 2);
    }

    private void parseLegacyCampaign(String code) {
        String campaign = code.substring("LEGACY:".length());
        try {
            Integer.parseInt(campaign.replace("CMP", "").replace("-", ""));
            throw new IllegalStateException("campaign " + campaign + " not found in legacy_campaigns");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed legacy campaign id '" + campaign + "'", e);
        }
    }

    public static class CouponException extends RuntimeException {
        public CouponException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
