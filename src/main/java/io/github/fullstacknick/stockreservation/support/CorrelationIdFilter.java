package io.github.fullstacknick.stockreservation.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    public static final String ATTRIBUTE = "correlationId";
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String correlationId = supplied != null && supplied.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                ? UUID.fromString(supplied).toString() : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        if (request.getRequestURI().startsWith("/reservations")) response.setHeader("Cache-Control", "no-store");
        long start = System.nanoTime();
        try {
            MDC.put(ATTRIBUTE, correlationId);
            chain.doFilter(request, response);
        } finally {
            LOG.atInfo().addKeyValue("correlationId", correlationId)
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("route", request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("externalRequestId", MDC.get("externalRequestId"))
                    .addKeyValue("reservationId", MDC.get("reservationId"))
                    .addKeyValue("sku", MDC.get("sku"))
                    .addKeyValue("quantity", MDC.get("quantity"))
                    .addKeyValue("outcome", MDC.get("outcome"))
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                    .log("HTTP request completed");
            MDC.clear();
        }
    }
}
