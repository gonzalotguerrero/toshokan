package io.github.gonzalotguerrero.toshokan.circulation.dto;

import java.time.LocalDate;
import java.util.UUID;

/** A late loan, with everything needed to chase it up: which book, who has it and how to reach them. */
public record OverdueLoan(
        UUID loanId,
        UUID bookId,
        UUID bookCopyId,
        String inventoryNumber,
        String title,
        UUID memberId,
        String cardNumber,
        String fullName,
        String email,
        String phone,
        LocalDate dueDate,
        long daysOverdue
) {
}
