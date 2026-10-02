package com.example.flights.api;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 {@code application/problem+json} responses.
 *
 * <p>Standard Spring MVC errors (malformed JSON, type mismatches, missing
 * parameters, unknown routes, client disconnects during SSE writes, ...) are
 * handled by the base class with their proper status. Unexpected exceptions become a generic 500 without
 * internal details.
 */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorHandler.class);
    private static final URI TYPE_VALIDATION = URI.create("urn:flightsignal:problem:validation");
    private static final URI TYPE_CONFLICT = URI.create("urn:flightsignal:problem:conflict");
    private static final URI TYPE_NOT_FOUND = URI.create("urn:flightsignal:problem:not-found");

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                exception.status(),
                exception.getMessage());
        problem.setType(switch (exception.status()) {
            case CONFLICT -> TYPE_CONFLICT;
            case NOT_FOUND -> TYPE_NOT_FOUND;
            default -> TYPE_VALIDATION;
        });
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException exception) {
        var message = exception.getMostSpecificCause().getMessage();

        if (message != null && message.contains("flight_instance_unique")) {
            var problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "That flight instance already exists.");
            problem.setType(TYPE_CONFLICT);
            return problem;
        }

        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The flight data did not pass database validation.");
        problem.setType(TYPE_VALIDATION);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        LOGGER.error("Unhandled request failure", exception);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please retry.");
    }
}
