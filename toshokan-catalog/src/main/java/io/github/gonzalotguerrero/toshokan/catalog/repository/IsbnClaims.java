package io.github.gonzalotguerrero.toshokan.catalog.repository;

import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByIsbn;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.DeleteOptions;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes sure no two books share an ISBN. Claims go through Cassandra's IF NOT EXISTS, so if two requests try
 * to claim the same ISBN at once, only one succeeds.
 *
 * <p>Releasing and taking over a claim are conditional too, on the book that holds it. That way nobody can free or
 * steal a claim that has already moved on to another book. It also keeps every write here a conditional one: a
 * plain write would be timestamped by our clock while the claim was timestamped by Cassandra's, and if Cassandra's
 * clock were ahead, the plain write would count as older and do nothing.
 */
@Component
public class IsbnClaims {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();

    private final CassandraOperations cassandra;

    public IsbnClaims(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Who holds an ISBN, and since when. */
    public record Claim(UUID bookId, Instant claimedAt) {
    }

    /** Returns {@code false} if another book already has this ISBN. */
    public boolean claim(String isbn, UUID bookId) {
        return cassandra.insert(new BookByIsbn(isbn, bookId), IF_NOT_EXISTS).wasApplied();
    }

    /** The current claim on an ISBN, or empty if nobody holds it. */
    public Optional<Claim> currentClaim(String isbn) {
        // The write time is when Cassandra stored the claim, in microseconds
        Row row = cassandra.execute(SimpleStatement.newInstance(
                "SELECT book_id, writetime(book_id) AS claimed_at FROM books_by_isbn WHERE isbn = ?", isbn)).one();
        if (row == null) {
            return Optional.empty();
        }
        Instant claimedAt = Instant.EPOCH.plus(row.getLong("claimed_at"), ChronoUnit.MICROS);
        return Optional.of(new Claim(row.getUuid("book_id"), claimedAt));
    }

    /** Moves the claim from a book that no longer exists to a new one. Returns {@code false} if it moved already. */
    public boolean takeOver(String isbn, UUID staleBookId, UUID newBookId) {
        return cassandra.getCqlOperations().execute(
                "UPDATE books_by_isbn SET book_id = ? WHERE isbn = ? IF book_id = ?", newBookId, isbn, staleBookId);
    }

    /** Frees the ISBN, but only if this book still holds it. Used when saving a book fails, and after deleting one. */
    public void release(String isbn, UUID bookId) {
        DeleteOptions onlyOurClaim = DeleteOptions.builder()
                // The column name, not the property name: Spring passes IF conditions through untranslated
                .ifCondition(Criteria.where("book_id").is(bookId))
                .build();
        cassandra.delete(new BookByIsbn(isbn, bookId), onlyOurClaim);
    }
}
