package io.github.gonzalotguerrero.toshokan.circulation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One loan in a copy's or member's history. While the loan is active, {@code returnedAt} and {@code returnedLate}
 * are null and {@code overdue} says whether it's late right now. Once it's returned, {@code overdue} is false and
 * {@code returnedLate} says whether it came back after its due date.
 */
public record LoanHistoryEntry(
        UUID loanId,
        UUID bookId,
        UUID bookCopyId,
        String inventoryNumber,
        UUID memberId,
        Instant lentAt,
        LocalDate dueDate,
        int renewals,
        Instant returnedAt,
        Boolean returnedLate,
        boolean overdue
) {
}
