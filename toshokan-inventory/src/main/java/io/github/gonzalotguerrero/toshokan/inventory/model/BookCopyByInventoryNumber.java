package io.github.gonzalotguerrero.toshokan.inventory.model;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * Maps an inventory number to the copy that has it. Like the catalog's ISBN table, it's only written with
 * IF NOT EXISTS, so a number can't be given to two copies.
 */
@Table("book_copies_by_inventory_number")
public record BookCopyByInventoryNumber(
        @PrimaryKey("inventory_number") String inventoryNumber,
        @Column("book_id") UUID bookId,
        @Column("book_copy_id") UUID bookCopyId
) {
}
