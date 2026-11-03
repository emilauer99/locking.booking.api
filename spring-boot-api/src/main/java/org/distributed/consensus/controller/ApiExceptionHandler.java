package org.distributed.consensus.controller;

import java.util.Map;
import java.util.stream.Collectors;

import org.distributed.consensus.service.BookingConflictException;
import org.distributed.consensus.service.BookingNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Central exception handling of the api. Every error is answered with an RFC 9457 problem
 * detail and logged, distinguished in three groups:
 * <ul>
 *     <li>conflicts (409): the request is valid, but collides with the current state or with a
 *     parallel request - expected behaviour of the locking, logged as WARN without stack trace</li>
 *     <li>invalid requests (400, 404, 405, ...): the client sent something wrong, logged as WARN</li>
 *     <li>technical errors (500, 503): a bug or the database is not available,
 *     logged as ERROR with stack trace, no internal details in the response</li>
 * </ul>
 * The built-in Spring MVC exceptions (validation, unreadable json, wrong path variable type,
 * unknown path, ...) are handled by {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // ---- conflicts --------------------------------------------------------------------------

    @ExceptionHandler(BookingConflictException.class)
    public ProblemDetail handleConflict(BookingConflictException e, WebRequest request) {
        log.warn("conflict on {}: {}", path(request), e.getMessage());
        return problem(HttpStatus.CONFLICT, "Booking conflict", e.getMessage());
    }

    /**
     * Fallback for optimistic/pessimistic locking failures (version conflict, deadlock,
     * lock timeout) that were not translated into a BookingConflictException by a service.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ProblemDetail handleConcurrencyFailure(ConcurrencyFailureException e, WebRequest request) {
        log.warn("concurrent modification on {}: {}", path(request), e.getMessage());
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "the booking was modified by a parallel request, please reload and try again");
    }

    /**
     * Fallback for constraint violations, e.g. the exclusion constraint against overlapping
     * bookings acting as safety net.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException e, WebRequest request) {
        log.warn("constraint violation on {}: {}", path(request), e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Booking conflict",
                "the request conflicts with an existing booking");
    }

    // ---- invalid requests -------------------------------------------------------------------

    @ExceptionHandler(BookingNotFoundException.class)
    public ProblemDetail handleNotFound(BookingNotFoundException e, WebRequest request) {
        log.warn("not found on {}: {}", path(request), e.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Booking not found", e.getMessage());
    }

    /**
     * Bean validation of the request body failed, the response lists the invalid fields.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = e.getBindingResult().getAllErrors().stream()
                .collect(Collectors.toMap(
                        error -> error instanceof FieldError field ? field.getField() : error.getObjectName(),
                        error -> String.valueOf(error.getDefaultMessage()),
                        (first, second) -> first + ", " + second));

        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Invalid request", "the request body is invalid");
        body.setProperty("errors", errors);
        return handleExceptionInternal(e, body, headers, status, request);
    }

    /**
     * All responses created by {@link ResponseEntityExceptionHandler} pass this method,
     * it is used to log them.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode status,
                                                             WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("technical error on {}", path(request), e);
        } else {
            log.warn("invalid request on {} ({}): {}", path(request), status.value(), e.getMessage());
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    // ---- technical errors -------------------------------------------------------------------

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessException.class})
    public ProblemDetail handleDatabaseError(Exception e, WebRequest request) {
        log.error("database error on {}", path(request), e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Database error",
                "the booking could not be processed, please try again later");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, WebRequest request) {
        log.error("unexpected error on {}", path(request), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "an unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    private static String path(WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            return servlet.getRequest().getMethod() + " " + servlet.getRequest().getRequestURI();
        }
        return request.getDescription(false);
    }
}
