package com.nexusops.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Assigns a safe request id (and correlation id) to every request, for logs, errors and audit. */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_CORRELATION_ID = "correlation_id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = sanitizeOrGenerate(request.getHeader(HEADER));
        String incomingCorrelation = request.getHeader(CORRELATION_HEADER);
        String correlationId = isSafe(incomingCorrelation) ? incomingCorrelation : requestId;

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CORRELATION_ID, correlationId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    public static String sanitizeOrGenerate(String candidate) {
        return isSafe(candidate) ? candidate : UUID.randomUUID().toString();
    }

    private static boolean isSafe(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches();
    }
}
