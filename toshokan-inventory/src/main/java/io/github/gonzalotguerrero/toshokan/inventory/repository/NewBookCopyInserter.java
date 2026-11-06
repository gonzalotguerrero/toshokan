package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

/**
 * Saves brand new copies with IF NOT EXISTS, even though the row never exists yet.
 *
 * <p>The condition is there because of how Cassandra timestamps writes. A conditional write takes its timestamp
 * from Cassandra's clock, a plain one from ours. Status changes are conditional (see
 * {@link BookCopyStatusChanges}), so if the insert were plain and the two clocks drifted even a little, a status
 * change could end up looking older than the insert it follows, and Cassandra would drop it without telling
 * anyone. Every write to {@code book_copies_by_book} has to be conditional for that reason.
 */
@Component
public class NewBookCopyInserter {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();

    private final CassandraOperations cassandra;

    public NewBookCopyInserter(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /**
     * Returns {@code false} if a copy with this id already exists. The id is a fresh random UUID, so in practice
     * that never happens, but when it does nothing was written.
     */
    public boolean insert(BookCopy copy) {
        return cassandra.insert(copy, IF_NOT_EXISTS).wasApplied();
    }
}
