package io.github.gonzalotguerrero.toshokan.inventory.repository;

import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyByInventoryNumber;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.DeleteOptions;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Filter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes sure no two copies share an inventory number. It works like the catalog's ISBN claims: with
 * IF NOT EXISTS, two requests going for the same number at once can't both get it.
 *
 * <p>Releasing and taking over a claim are conditional on the copy that holds it, so nobody can free or steal a
 * claim that has already moved on. That also keeps every write here conditional: a plain write is timestamped by
 * our clock, the claim by Cassandra's, and if Cassandra's clock were ahead the plain write would do nothing.
 */
@Component
public class InventoryNumberClaims {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();

    private final CassandraOperations cassandra;

    public InventoryNumberClaims(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Which copy holds an inventory number, and since when. */
    public record Claim(UUID bookId, UUID bookCopyId, Instant claimedAt) {
    }

    /** Returns {@code false} if another copy already has this number. */
    public boolean claim(String inventoryNumber, UUID bookId, UUID bookCopyId) {
        return cassandra.insert(new BookCopyByInventoryNumber(inventoryNumber, bookId, bookCopyId), IF_NOT_EXISTS)
                .wasApplied();
    }

    /** The current claim on a number, or empty if nobody holds it. */
    public Optional<Claim> currentClaim(String inventoryNumber) {
        // The write time is when Cassandra stored the claim, in microseconds
        Row row = cassandra.execute(SimpleStatement.newInstance(
                "SELECT book_id, book_copy_id, writetime(book_copy_id) AS claimed_at"
                        + " FROM book_copies_by_inventory_number WHERE inventory_number = ?", inventoryNumber)).one();
        if (row == null) {
            return Optional.empty();
        }
        Instant claimedAt = Instant.EPOCH.plus(row.getLong("claimed_at"), ChronoUnit.MICROS);
        return Optional.of(new Claim(row.getUuid("book_id"), row.getUuid("book_copy_id"), claimedAt));
    }

    /** Moves the claim from a copy that doesn't exist to a new one. Returns {@code false} if it moved already. */
    public boolean takeOver(String inventoryNumber, Claim stale, UUID newBookId, UUID newBookCopyId) {
        return cassandra.getCqlOperations().execute(
                "UPDATE book_copies_by_inventory_number SET book_id = ?, book_copy_id = ?"
                        + " WHERE inventory_number = ? IF book_id = ? AND book_copy_id = ?",
                newBookId, newBookCopyId, inventoryNumber, stale.bookId(), stale.bookCopyId());
    }

    /** Frees the number, but only if this copy still holds it. Used when the claim worked but saving the copy didn't. */
    public void release(String inventoryNumber, UUID bookId, UUID bookCopyId) {
        DeleteOptions onlyOurClaim = DeleteOptions.builder()
                // Column names, not property names: Spring passes IF conditions through untranslated. Both go in
                // one Filter, because a second ifCondition call would replace the first instead of adding to it
                .ifCondition(Filter.from(Criteria.where("book_id").is(bookId),
                        Criteria.where("book_copy_id").is(bookCopyId)))
                .build();
        cassandra.delete(new BookCopyByInventoryNumber(inventoryNumber, bookId, bookCopyId), onlyOurClaim);
    }
}
