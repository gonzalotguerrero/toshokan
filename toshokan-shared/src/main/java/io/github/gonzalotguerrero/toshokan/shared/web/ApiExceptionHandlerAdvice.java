package io.github.gonzalotguerrero.toshokan.shared.web;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
}
