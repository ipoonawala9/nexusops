package com.nexusops.shared.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An expected, client-facing failure. Mapped to problem+json by GlobalExceptionHandler. */
public class ApiProblem extends RuntimeException {

    public record FieldError(String field, String message) {}

    private final HttpStatus status;
    private final List<FieldError> errors;

    protected ApiProblem(HttpStatus status, String detail, List<FieldError> errors) {
        super(detail);
        this.status = status;
        this.errors = List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public List<FieldError> errors() {
        return errors;
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
