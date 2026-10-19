package com.example.shop.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.shop.chaos.ChaosState;
import com.example.shop.chaos.Scenario;
import com.example.shop.web.NotFoundException;

import io.opentelemetry.api.trace.Span;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record OrderResult(long orderId, BigDecimal total, String paymentId, String customerTier) {}

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final PaymentClient payments;
    private final CouponService coupons;
    private final TransactionTemplate tx;
    private final ChaosState chaos;

    public OrderService(OrderRepository orders, InventoryService inventory, PaymentClient payments,
                        CouponService coupons, TransactionTemplate tx, ChaosState chaos) {
        this.orders = orders;
        this.inventory = inventory;
        this.payments = payments;
        this.coupons = coupons;
        this.tx = tx;
        this.chaos = chaos;
    }

    public OrderResult checkout(OrderRequest request) {
        long customerId = request.customerId() != null ? request.customerId() : 1L;
        String method = request.paymentMethod() != null ? request.paymentMethod() : "card";
        String tier = orders.customerTier(customerId);

        Span span = Span.current();
        span.setAttribute("customer.tier", tier);
        span.setAttribute("payment.method", method);
        span.setAttribute("order.item_count", request.items().size());

        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderRequest.Item item : request.items()) {
            subtotal = subtotal.add(orders.priceOf(item.productId()).multiply(BigDecimal.valueOf(item.quantity())));
        }
        BigDecimal discount = coupons.discountRate(request.couponCode());
        BigDecimal total = subtotal.multiply(BigDecimal.ONE.subtract(discount)).setScale(2, RoundingMode.HALF_UP);

        inventory.reserve(request.items());
        String paymentId = payments.charge(customerId, total, method);

        long orderId = tx.execute(status -> {
            long id = orders.insertOrder(customerId, total, "PAID");
            for (OrderRequest.Item item : request.items()) {
                orders.insertItem(id, item.productId(), item.quantity(), orders.priceOf(item.productId()));
            }
            if (chaos.enabled(Scenario.POOL_EXHAUSTION)) {
                refreshLoyaltyAndRenderReceipt(customerId, id);
            }
            return id;
        });

        log.info("Order {} placed by customer {} ({}): total={} payment={}", orderId, customerId, tier, total, paymentId);
        return new OrderResult(orderId, total, paymentId, tier);
    }

    /**
     * Runs inside the order transaction, so the JDBC connection is held for the whole time:
     * a heavy ranking query followed by slow receipt rendering. Under load the pool (10) drains
     * and every other endpoint starts waiting for a connection.
     */
    private void refreshLoyaltyAndRenderReceipt(long customerId, long orderId) {
        long rank = orders.loyaltyRank(customerId);
        String newTier = rank <= 2_000 ? "gold" : rank <= 8_000 ? "silver" : "bronze";
        orders.updateCustomerTier(customerId, newTier);
        renderReceiptPdf(orderId);
    }

    private void renderReceiptPdf(long orderId) {
        try {
            Thread.sleep(chaos.intensity(Scenario.POOL_EXHAUSTION) * 50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, Object> find(long id) {
        return orders.findOrder(id).orElseThrow(() -> new NotFoundException("order " + id));
    }
}
