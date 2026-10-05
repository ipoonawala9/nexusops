package com.nexusops.shared.ratelimit;

import com.nexusops.shared.web.ApiProblem;
import java.util.List;
import org.springframework.http.HttpStatus;

public class RateLimitExceeded extends ApiProblem {

    private final long retryAfterSeconds;

    public RateLimitExceeded(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again in " + retryAfterSeconds + " seconds.", List.of());
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
