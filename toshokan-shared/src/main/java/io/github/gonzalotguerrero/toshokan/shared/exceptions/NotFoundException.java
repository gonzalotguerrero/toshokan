package io.github.gonzalotguerrero.toshokan.shared.exceptions;

/**
 * Whatever the client asked for doesn't exist. Results in a 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
