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
 * One loan of a copy, in the copy's loan history. Each copy's loans share a partition, newest first.
 *
 * <p>The row is written before the copy itself changes: once when the copy is lent, and again (with
 * {@code returned_at}) just before it's returned. That order means a loan can never be missing from the history,
 * but it also means a row can describe a lend or return that ended up not happening.
 * {@link io.github.gonzalotguerrero.toshokan.circulation.service.LoanHistoryService} sorts that out when reading,
 * by checking the row against the copy. The due date and renewals here are as they were when the row was last
 * written; while the loan is still active, the copy has the current ones.
 */
@Table("loans_by_copy")
public record LoanByCopy(

        @PrimaryKeyColumn(name = "book_copy_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        UUID bookCopyId,

        @PrimaryKeyColumn(name = "lent_at", ordinal = 1, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
        Instant lentAt,

        @PrimaryKeyColumn(name = "loan_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
        UUID loanId,

        @Column("book_id") UUID bookId,
        @Column("inventory_number") String inventoryNumber,
        @Column("member_id") UUID memberId,
        @Column("due_date") LocalDate dueDate,
        @Column("renewals") int renewals,
        // Null until the loan is returned
        @Column("returned_at") Instant returnedAt
) {
}
