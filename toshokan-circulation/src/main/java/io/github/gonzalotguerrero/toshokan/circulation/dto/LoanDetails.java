package io.github.gonzalotguerrero.toshokan.circulation.dto;

import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyLoan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** What the API returns for a loan: which copy, who has it, and until when. */
public record LoanDetails(
        UUID loanId,
        UUID bookId,
        UUID bookCopyId,
        String inventoryNumber,
        UUID memberId,
        Instant lentAt,
        LocalDate dueDate,
        int renewals,
        boolean overdue
) {

    /** {@code copy} has to be lent out. */
    public static LoanDetails from(BookCopyDetails copy) {
        BookCopyLoan loan = copy.loan();
        return new LoanDetails(loan.loanId(), copy.bookId(), copy.bookCopyId(), copy.inventoryNumber(),
                loan.memberId(), loan.lentAt(), loan.dueDate(), loan.renewals(), loan.overdue());
    }
}
