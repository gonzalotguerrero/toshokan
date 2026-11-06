package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import java.time.Instant;
import java.util.UUID;

/** What the API returns for a book copy. */
public record BookCopyDetails(
        UUID bookCopyId,
        UUID bookId,
        String inventoryNumber,
        BookCopyStatus status,
        Instant addedAt
) {

    public static BookCopyDetails from(BookCopy copy) {
        return new BookCopyDetails(copy.bookCopyId(), copy.bookId(), copy.inventoryNumber(), copy.status(),
                copy.addedAt());
    }
}
