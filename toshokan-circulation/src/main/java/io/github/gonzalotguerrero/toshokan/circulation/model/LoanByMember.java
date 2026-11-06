package io.github.gonzalotguerrero.toshokan.circulation.model;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The same loan as {@link LoanByCopy}, filed under the member instead, so a member's whole history is one
 * partition, newest first. Both rows are always written together in one logged batch, so they can't disagree.
 */
@Table("loans_by_member")
public record LoanByMember(

        @PrimaryKeyColumn(name = "member_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        UUID memberId,

        @PrimaryKeyColumn(name = "lent_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
        Instant lentAt,

        @PrimaryKeyColumn(name = "loan_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
        UUID loanId,

        @Column("book_id") UUID bookId,
        @Column("book_copy_id") UUID bookCopyId,
        @Column("inventory_number") String inventoryNumber,
        @Column("due_date") LocalDate dueDate,
        @Column("renewals") int renewals,
        @Column("returned_at") Instant returnedAt
) {

    public static LoanByMember of(LoanByCopy loan) {
        return new LoanByMember(loan.memberId(), loan.lentAt(), loan.loanId(), loan.bookId(), loan.bookCopyId(),
                loan.inventoryNumber(), loan.dueDate(), loan.renewals(), loan.returnedAt());
    }
}
