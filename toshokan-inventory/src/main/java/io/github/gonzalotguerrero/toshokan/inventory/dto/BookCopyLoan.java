package io.github.gonzalotguerrero.toshokan.inventory.dto;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Who has a copy and until when. {@code overdue} isn't stored anywhere: it's worked out from the due date every
 * time a copy is read, so it's never out of date. A copy becomes overdue the day after its due date.
 */
public record BookCopyLoan(
        UUID loanId,
        UUID memberId,
        Instant lentAt,
        LocalDate dueDate,
        int renewals,
        boolean overdue
) {

    /** Returns {@code null} if the copy isn't lent out. */
    public static BookCopyLoan of(BookCopy copy, LocalDate today) {
        if (copy.loanId() == null) {
            return null;
        }
        return new BookCopyLoan(copy.loanId(), copy.memberId(), copy.lentAt(), copy.dueDate(), copy.renewals(),
                today.isAfter(copy.dueDate()));
    }
}
