package com.example.shop.order;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Calls the external payment gateway. The retry policy is the anti-pattern on show:
 * short timeout, immediate retries, no backoff, no circuit breaker.
 */
@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);
    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final URI chargeUri;
    private final Duration timeout;

    public PaymentClient(@Value("${shop.payment.url}") String baseUrl,
                         @Value("${shop.payment.timeout-ms}") long timeoutMs) {
        this.chargeUri = URI.create(baseUrl + "/v1/charges");
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public String charge(long customerId, BigDecimal amount, String method) {
        String body = "{\"customerId\":%d,\"amount\":\"%s\",\"method\":\"%s\"}".formatted(customerId, amount, method);
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(chargeUri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                last = new PaymentException("payment gateway returned " + response.statusCode());
            } catch (HttpTimeoutException e) {
                last = e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                last = e;
            }
            log.warn("Payment attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, last.toString());
        }
        throw new PaymentException("payment failed after " + MAX_ATTEMPTS + " attempts", last);
    }

    public static class PaymentException extends RuntimeException {
        public PaymentException(String message) {
            super(message);
        }

        public PaymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
