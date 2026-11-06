package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.UpdateOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.CriteriaDefinition;
import org.springframework.data.cassandra.core.query.Filter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes changes to a copy's status and loan, but only if nobody else changed the copy since we read it.
 *
 * <p>The write is an UPDATE with IF status = (the status we saw), plus, for a lent copy, the loan id and number of
 * renewals we saw. Without that check, marking a copy as damaged could quietly undo a loan that happened a moment
 * earlier, or two renewals at once could both count as the first. Because this write is conditional, new copies
 * have to be inserted conditionally too; {@link NewBookCopyInserter} explains why.
 */
@Component
public class BookCopyStatusChanges {

    private final CassandraOperations cassandra;

    public BookCopyStatusChanges(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Returns {@code false} if the copy's status had already changed, in which case nothing was written. */
    public boolean change(BookCopy copy, BookCopyStatus newStatus) {
        return replace(copy, copy.withStatus(newStatus));
    }

    /**
     * Saves {@code updated} in place of {@code current}. Returns {@code false} if the copy no longer looks like
     * {@code current}, in which case nothing was written.
     */
    public boolean replace(BookCopy current, BookCopy updated) {
        // IF conditions take column names as they are; Spring doesn't translate property names here
        List<CriteriaDefinition> unchanged = new ArrayList<>();
        unchanged.add(Criteria.where("status").is(current.status().name()));
        if (current.loanId() != null) {
            unchanged.add(Criteria.where("loan_id").is(current.loanId()));
            unchanged.add(Criteria.where("renewals").is(current.renewals()));
        }
        UpdateOptions onlyIfUnchanged = UpdateOptions.builder().ifCondition(Filter.from(unchanged)).build();
        return cassandra.update(updated, onlyIfUnchanged).wasApplied();
    }
}
