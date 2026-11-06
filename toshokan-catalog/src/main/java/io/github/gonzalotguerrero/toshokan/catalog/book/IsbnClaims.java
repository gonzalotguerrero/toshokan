package io.github.gonzalotguerrero.toshokan.catalog.book;

import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.DeleteOptions;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Makes sure no two books share an ISBN. Claims go through Cassandra's IF NOT EXISTS, so if two requests try
 * to claim the same ISBN at once, only one succeeds.
 *
 * <p>Releasing a claim is conditional too (IF EXISTS). A plain delete would be timestamped by our clock while the
 * claim was timestamped by Cassandra's, and if Cassandra's clock were ahead, the delete would count as older than
 * the claim and do nothing.
 */
@Component
class IsbnClaims {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();
    private static final DeleteOptions IF_EXISTS = DeleteOptions.builder().withIfExists().build();

    private final CassandraOperations cassandra;

    public IsbnClaims(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Returns {@code false} if another book already has this ISBN. */
    public boolean claim(String isbn, UUID bookId) {
        return cassandra.insert(new BookByIsbn(isbn, bookId), IF_NOT_EXISTS).wasApplied();
    }

    /** Frees the ISBN again. Used when the claim worked but saving the book didn't. */
    public void release(String isbn, UUID bookId) {
        cassandra.delete(new BookByIsbn(isbn, bookId), IF_EXISTS);
    }
}
