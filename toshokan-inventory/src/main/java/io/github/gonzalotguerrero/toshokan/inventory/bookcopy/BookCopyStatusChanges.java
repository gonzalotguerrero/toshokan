package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.UpdateOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Writes status changes, but only if nobody else changed the copy's status since we read it.
 *
 * <p>The write is an UPDATE with IF status = (the status we saw). Without that check, marking a copy as damaged
 * could quietly undo a loan that happened a moment earlier. Because this write is conditional, new copies have to
 * be inserted conditionally too; {@link NewBookCopyInserter} explains why.
 */
@Component
class BookCopyStatusChanges {

    private final CassandraOperations cassandra;

    public BookCopyStatusChanges(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Returns {@code false} if the copy's status had already changed, in which case nothing was written. */
    public boolean change(BookCopy copy, BookCopyStatus newStatus) {
        UpdateOptions onlyIfUnchanged = UpdateOptions.builder()
                .ifCondition(Criteria.where("status").is(copy.status().name()))
                .build();
        return cassandra.update(copy.withStatus(newStatus), onlyIfUnchanged).wasApplied();
    }
}
