package io.github.gonzalotguerrero.toshokan.catalog.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;

/**
 * Lets another module decide whether a book can be deleted right now. The catalog can't see copies (inventory
 * depends on the catalog, not the other way round), so inventory implements this: it refuses while the book still
 * has copies that aren't withdrawn, and makes sure no copy is added or put back while the deletion is written.
 */
public interface BookDeletionGuard {

    /**
     * Runs {@code deletion} if the book can be deleted, or throws a
     * {@link io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException} explaining why not.
     */
    void runGuarded(BookDetails book, Runnable deletion);
}
