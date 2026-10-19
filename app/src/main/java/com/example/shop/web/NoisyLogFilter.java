package com.example.shop.web;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.shop.chaos.ChaosState;
import com.example.shop.chaos.Scenario;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * With log-noise enabled every API request emits a burst of meaningless WARN lines,
 * the kind that bury the one ERROR that matters.
 */
@Component
public class NoisyLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.example.shop.featureflags.FlagClient");

    private static final String[] MESSAGES = {
            "Feature flag service unreachable, falling back to cached flags (age=%ds)",
            "Flag 'new-checkout-ui' evaluated with stale context, user segment unknown",
            "Retrying flag sync in %ds: upstream returned HTTP 503",
            "Deprecated config key 'shop.legacy.pricing.enabled' is still set",
    };

    private final ChaosState chaos;

    public NoisyLogFilter(ChaosState chaos) {
        this.chaos = chaos;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (chaos.enabled(Scenario.LOG_NOISE)) {
            int lines = 1 + chaos.intensity(Scenario.LOG_NOISE) / 10;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < lines; i++) {
                log.warn(MESSAGES[r.nextInt(MESSAGES.length)].formatted(r.nextInt(5, 600)));
            }
        }
        chain.doFilter(request, response);
    }
}
