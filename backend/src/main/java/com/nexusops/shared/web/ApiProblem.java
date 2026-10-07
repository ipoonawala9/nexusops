package com.nexusops.shared.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** An expected, client-facing failure. Mapped to problem+json by GlobalExceptionHandler. */
public class ApiProblem extends RuntimeException {

    public record FieldError(String field, String message) {}

    private final HttpStatus status;
    private final List<FieldError> errors;
    private final Map<String, Object> properties;

    protected ApiProblem(HttpStatus status, String detail, List<FieldError> errors) {
        this(status, detail, errors, Map.of());
    }

    private ApiProblem(HttpStatus status, String detail, List<FieldError> errors, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.errors = List.copyOf(errors);
        this.properties = Map.copyOf(properties);
    }

    /** Extra RFC 9457 members (e.g. duplicate candidates). */
    public Map<String, Object> properties() {
        return properties;
    }

    /** A copy carrying one more problem member. Subclass-specific behaviour (e.g. Retry-After) is not kept. */
    public ApiProblem withProperty(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(properties);
        merged.put(name, value);
        return new ApiProblem(status, getMessage(), errors, merged);
    }

    /** A copy whose field errors are prefixed (e.g. "person." + "firstName"), for nested request objects. */
    public ApiProblem prefixed(String prefix) {
        return new ApiProblem(status, getMessage(),
                errors.stream().map(e -> new FieldError(prefix + e.field(), e.message())).toList(), properties);
    }

    public HttpStatus status() {
        return status;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public static ApiProblem payloadTooLarge(String detail) {
        return new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, detail, List.of());
    }

    public static ApiProblem badRequest(String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, detail, List.of());
    }

    public static ApiProblem badRequestField(String field, String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "Request validation failed.", List.of(new FieldError(field, message)));
    }

    public static ApiProblem unauthorized(String detail) {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, detail, List.of());
    }

    public static ApiProblem forbidden(String detail) {
        return new ApiProblem(HttpStatus.FORBIDDEN, detail, List.of());
    }

    public static ApiProblem notFound(String detail) {
        return new ApiProblem(HttpStatus.NOT_FOUND, detail, List.of());
    }

    public static ApiProblem serviceUnavailable(String detail) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, detail, List.of());
    }

    public static ApiProblem conflict(String detail) {
        return new ApiProblem(HttpStatus.CONFLICT, detail, List.of());
    }

    public static ApiProblem conflictField(String field, String message) {
        return new ApiProblem(HttpStatus.CONFLICT, message, List.of(new FieldError(field, message)));
    }
}
