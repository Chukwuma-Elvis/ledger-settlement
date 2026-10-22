package com.ledger.settlement;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Turns every failure into an RFC 9457 problem detail (application/problem+json) carrying
 * type, title, status, detail and instance, so clients see one error shape everywhere.
 */
@RestControllerAdvice
public class ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    static final URI VALIDATION_TYPE = URI.create("/problems/validation-error");
    static final URI UNKNOWN_MERCHANT_TYPE = URI.create("/problems/unknown-merchant");
    static final URI MALFORMED_BODY_TYPE = URI.create("/problems/malformed-body");
    static final URI INTERNAL_ERROR_TYPE = URI.create("/problems/internal-error");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        // Sorted so the detail text is stable: "amountMinor: must be greater than 0; ..."
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, this::messageOf, (first, second) -> first, TreeMap::new));
        String detail = errors.entrySet().stream()
                .map(error -> error.getKey() + ": " + error.getValue())
                .collect(Collectors.joining("; "));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_TYPE, "Invalid request", detail, request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(UnknownMerchantException.class)
    public ProblemDetail handleUnknownMerchant(UnknownMerchantException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, UNKNOWN_MERCHANT_TYPE, "Unknown merchant", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, MALFORMED_BODY_TYPE, "Malformed request body",
                "The request body is missing or is not valid JSON for this endpoint", request);
    }

    // Everything else: Spring MVC's own exceptions (missing parameter, wrong method, no such
    // route) already carry a ProblemDetail; anything unexpected becomes a generic 500.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleOther(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            ProblemDetail problem = errorResponse.getBody();
            problem.setInstance(URI.create(request.getRequestURI()));
            return problem;
        }
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        // Never leak exception messages or stack traces to the client.
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_TYPE, "Internal error",
                "An unexpected error occurred", request);
    }

    private ProblemDetail problem(HttpStatus status, URI type, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private String messageOf(FieldError error) {
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
    }
}
