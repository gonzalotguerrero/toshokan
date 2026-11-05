package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Makes sure no two copies share an inventory number. It works like the catalog's ISBN claims: with
 * IF NOT EXISTS, two requests going for the same number at once can't both get it.
 */
@Component
class InventoryNumberClaims {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();

    private final CassandraOperations cassandra;

    InventoryNumberClaims(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Returns {@code false} if another copy already has this number. */
    boolean claim(String inventoryNumber, UUID bookId, UUID bookCopyId) {
        return cassandra.insert(new BookCopyByInventoryNumber(inventoryNumber, bookId, bookCopyId), IF_NOT_EXISTS)
                .wasApplied();
    }

    /** Frees the number again. Used when the claim worked but saving the copy didn't. */
    void release(String inventoryNumber) {
        cassandra.deleteById(inventoryNumber, BookCopyByInventoryNumber.class);
    }
}
