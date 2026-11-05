package io.github.gonzalotguerrero.toshokan.shared.web;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.TreeMap;

/**
 * Turns custom exceptions into standard problem-detail error bodies.
 */
@RestControllerAdvice
public class ApiExceptionHandlerAdvice {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandlerAdvice.class);

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException exception) {
        log.info("Rejected request: {}", exception.getMessage());
        if (exception.getCause() != null) {
            // e.g. the exact reason Cassandra didn't accept a page token
            log.debug("Rejection cause", exception.getCause());
        }
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage());
    }

    /** Handles request bodies that fail bean validation. Each invalid field is listed under {@code errors}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidFields(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        log.info("Rejected request: invalid fields {}", errors.keySet());
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "Some fields are invalid");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException exception) {
        log.info("Not found: {}", exception.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Not found", exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException exception) {
        log.info("Conflict: {}", exception.getMessage());
        return problem(HttpStatus.CONFLICT, "Conflict", exception.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
