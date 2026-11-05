package io.github.gonzalotguerrero.toshokan.shared.exceptions;

/**
 * The request parsed fine but doesn't make sense
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
