package io.github.gonzalotguerrero.toshokan.catalog.book;

import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Makes sure no two books share an ISBN. Claims go through Cassandra's IF NOT EXISTS, so if two requests try
 * to claim the same ISBN at once, only one succeeds.
 */
@Component
class IsbnClaims {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();

    private final CassandraOperations cassandra;

    public IsbnClaims(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Returns {@code false} if another book already has this ISBN. */
    public boolean claim(String isbn, UUID bookId) {
        return cassandra.insert(new BookByIsbn(isbn, bookId), IF_NOT_EXISTS).wasApplied();
    }

    /** Frees the ISBN again. Used when the claim worked but saving the book didn't. */
    public void release(String isbn) {
        cassandra.deleteById(isbn, BookByIsbn.class);
    }
}
