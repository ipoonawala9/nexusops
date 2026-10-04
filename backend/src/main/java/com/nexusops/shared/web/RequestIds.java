package com.nexusops.shared.web;

import org.slf4j.MDC;

/** Read access to the current request's ids (set by {@link RequestIdFilter}). */
public final class RequestIds {

    private RequestIds() {}

    public static String current() {
        String id = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        return id != null ? id : "none";
    }

    public static String correlationId() {
        String id = MDC.get(RequestIdFilter.MDC_CORRELATION_ID);
        return id != null ? id : current();
    }
}
