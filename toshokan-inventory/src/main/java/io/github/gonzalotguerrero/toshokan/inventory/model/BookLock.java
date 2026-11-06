package io.github.gonzalotguerrero.toshokan.inventory.model;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * A row here means the book is being deleted, or getting a copy added or put back, right now. See
 * {@link io.github.gonzalotguerrero.toshokan.inventory.repository.BookLocks}.
 */
@Table("book_locks")
public record BookLock(
        @PrimaryKey("book_id") UUID bookId,
        // Tells our own lock apart from someone else's, so we never release a lock we don't hold
        @Column("lock_id") UUID lockId
) {
}
