package io.github.gonzalotguerrero.toshokan.inventory.model;

import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One physical copy of a book. All the copies of a book live in the same partition, so getting every copy of
 * a book with its status takes a single read.
 */
@Table("book_copies_by_book")
public record BookCopy(
        @PrimaryKeyColumn(name = "book_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        UUID bookId,

        @PrimaryKeyColumn(name = "book_copy_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
        UUID bookCopyId,

        @Column("inventory_number") String inventoryNumber,
        @Column("status") BookCopyStatus status,
        @Column("added_at") Instant addedAt
) {

    public BookCopy withStatus(BookCopyStatus newStatus) {
        return new BookCopy(bookId, bookCopyId, inventoryNumber, newStatus, addedAt);
    }
}
