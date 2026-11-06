package io.github.gonzalotguerrero.toshokan.inventory.dto;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** What the API returns for a book copy. {@code loan} is only there while the copy is lent out. */
public record BookCopyDetails(
        UUID bookCopyId,
        UUID bookId,
        String inventoryNumber,
        BookCopyStatus status,
        Instant addedAt,
        BookCopyLoan loan
) {

    /** {@code today} is the library's date, used to tell whether the loan is overdue. */
    public static BookCopyDetails from(BookCopy copy, LocalDate today) {
        return new BookCopyDetails(copy.bookCopyId(), copy.bookId(), copy.inventoryNumber(), copy.status(),
                copy.addedAt(), BookCopyLoan.of(copy, today));
    }
}
