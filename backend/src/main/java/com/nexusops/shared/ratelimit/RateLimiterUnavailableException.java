package com.nexusops.shared.ratelimit;

public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(Throwable cause) {
        super("Rate limiter unavailable", cause);
    }
}
