package com.nexusops.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Factory for RFC 9457 problem details that always carry the request id. */
public final class ProblemDetails {

    public static final String REQUEST_ID = "requestId";

    private ProblemDetails() {}

    public static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return withRequestId(problem);
    }

    public static ProblemDetail withRequestId(ProblemDetail problem) {
        problem.setProperty(REQUEST_ID, RequestIds.current());
        return problem;
    }

    /** Flat map form for writers that bypass Spring MVC message converters (security filters). */
    public static Map<String, Object> toMap(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType() == null ? "about:blank" : problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }
}
