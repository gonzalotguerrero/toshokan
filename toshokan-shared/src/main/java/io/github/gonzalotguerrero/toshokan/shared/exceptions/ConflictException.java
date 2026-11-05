package io.github.gonzalotguerrero.toshokan.shared.exceptions;

/**
 * The request clashes with something we already have, e.g. an ISBN that belongs to another book. Results in
 * a 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
