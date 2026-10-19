package com.example.shop.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.shop.order.CouponService;
import com.example.shop.order.PaymentClient;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/** Maps failures to HTTP status codes and records the exception on the server span. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PaymentClient.PaymentException.class)
    public ResponseEntity<Map<String, String>> payment(PaymentClient.PaymentException e) {
        log.error("Checkout failed: {}", e.getMessage(), e);
        return error(HttpStatus.BAD_GATEWAY, e);
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<Map<String, String>> database(NestedRuntimeException e) {
        log.error("Database access failed: {}", e.getMostSpecificCause().getMessage(), e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, e);
    }

    @ExceptionHandler(CouponService.CouponException.class)
    public ResponseEntity<Map<String, String>> coupon(CouponService.CouponException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, Exception e) {
        Span span = Span.current();
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, e.getMessage());
        return ResponseEntity.status(status).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
