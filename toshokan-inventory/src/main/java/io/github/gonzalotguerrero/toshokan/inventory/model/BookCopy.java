package io.github.gonzalotguerrero.toshokan.inventory.model;

import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Indexed;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One physical copy of a book. All the copies of a book live in the same partition, so getting every copy of
 * a book with its status takes a single read.
 *
 * <p>While a copy is LENT, the row also says who has it, since when and until when. Those columns are set and
 * cleared by the same conditional write that changes the status, so a copy can never be LENT without a loan, or
 * have a loan while it's on the shelf. Circulation keeps a loan history too, but it's only a record: when the
 * history and this row disagree about which loan is active, this row wins.
 *
 * <p>{@code member_id} and {@code status} have secondary indexes, which is how we find everything one member has
 * out, and every copy that's lent out (for the overdue list). Cassandra updates an index together with its row,
 * so they can't fall behind either. An index on a column with so few values would be a poor fit for a big cluster,
 * but one library's copies are a small table.
 */
@Table("book_copies_by_book")
public record BookCopy(
        @PrimaryKeyColumn(name = "book_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        UUID bookId,

        @PrimaryKeyColumn(name = "book_copy_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
        UUID bookCopyId,

        @Column("inventory_number") String inventoryNumber,
        @Indexed("book_copies_by_book_status_idx") @Column("status") BookCopyStatus status,
        @Column("added_at") Instant addedAt,

        // Everything below describes the current loan, and is null unless the copy is LENT
        @Column("loan_id") UUID loanId,
        @Indexed("book_copies_by_book_member_id_idx") @Column("member_id") UUID memberId,
        @Column("lent_at") Instant lentAt,
        @Column("due_date") LocalDate dueDate,
        @Column("renewals") Integer renewals
) {

    /** A copy that was just added: on the shelf, never lent. */
    public static BookCopy newCopy(UUID bookId, UUID bookCopyId, String inventoryNumber, Instant addedAt) {
        return new BookCopy(bookId, bookCopyId, inventoryNumber, BookCopyStatus.AVAILABLE, addedAt,
                null, null, null, null, null);
    }

    /** For manual status changes, which never involve a loan. */
    public BookCopy withStatus(BookCopyStatus newStatus) {
        return new BookCopy(bookId, bookCopyId, inventoryNumber, newStatus, addedAt,
                loanId, memberId, lentAt, dueDate, renewals);
    }

    public BookCopy lentTo(UUID newLoanId, UUID borrowerId, Instant when, LocalDate due) {
        return new BookCopy(bookId, bookCopyId, inventoryNumber, BookCopyStatus.LENT, addedAt,
                newLoanId, borrowerId, when, due, 0);
    }

    public BookCopy renewedUntil(LocalDate newDueDate) {
        return new BookCopy(bookId, bookCopyId, inventoryNumber, status, addedAt,
                loanId, memberId, lentAt, newDueDate, renewals + 1);
    }

    /** Back on the shelf, with every trace of the loan cleared from the row. */
    public BookCopy returned() {
        return new BookCopy(bookId, bookCopyId, inventoryNumber, BookCopyStatus.AVAILABLE, addedAt,
                null, null, null, null, null);
    }
}
