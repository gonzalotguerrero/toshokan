package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyByInventoryNumber;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.DeleteOptions;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Makes sure no two copies share an inventory number. It works like the catalog's ISBN claims: with
 * IF NOT EXISTS, two requests going for the same number at once can't both get it.
 *
 * <p>Releasing a claim is conditional too (IF EXISTS), for the same reason as with ISBNs: a plain delete is
 * timestamped by our clock, the claim by Cassandra's, and if Cassandra's clock were ahead the delete would do
 * nothing.
 */
@Component
public class InventoryNumberClaims {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();
    private static final DeleteOptions IF_EXISTS = DeleteOptions.builder().withIfExists().build();

    private final CassandraOperations cassandra;

    public InventoryNumberClaims(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Returns {@code false} if another copy already has this number. */
    public boolean claim(String inventoryNumber, UUID bookId, UUID bookCopyId) {
        return cassandra.insert(new BookCopyByInventoryNumber(inventoryNumber, bookId, bookCopyId), IF_NOT_EXISTS)
                .wasApplied();
    }

    /** Frees the number again. Used when the claim worked but saving the copy didn't. */
    public void release(String inventoryNumber, UUID bookId, UUID bookCopyId) {
        cassandra.delete(new BookCopyByInventoryNumber(inventoryNumber, bookId, bookCopyId), IF_EXISTS);
    }
}
