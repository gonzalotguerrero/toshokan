package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookLock;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.DeleteOptions;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A short lock per book, so deleting a book can't overlap with a copy being added to it or put back from
 * withdrawn. Without it, a delete could check "every copy is withdrawn", a copy could be added right after, and
 * the delete would go ahead and leave a copy on the shelf of a book that no longer exists.
 *
 * <p>It works like the member lending lock: a row inserted with IF NOT EXISTS and a TTL, released only if it's
 * still ours. If releasing ever fails, the TTL clears it within a few seconds, and the lock holds no data that
 * could end up wrong.
 */
@Component
public class BookLocks {

    private static final Logger log = LoggerFactory.getLogger(BookLocks.class);

    // Far longer than any of the guarded operations takes, and short enough that a leftover lock barely matters
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final InsertOptions TAKE_LOCK = InsertOptions.builder().withIfNotExists().ttl(LOCK_TTL).build();

    private final CassandraOperations cassandra;

    public BookLocks(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /**
     * Runs {@code work} while holding the book's lock. If someone else holds it, nothing runs and the request is
     * refused with a conflict, since retrying a moment later will work.
     */
    public <T> T whileLocked(UUID bookId, Supplier<T> work) {
        UUID lockId = UUID.randomUUID();
        if (!cassandra.insert(new BookLock(bookId, lockId), TAKE_LOCK).wasApplied()) {
            throw new ConflictException("Something else is being done to book " + bookId
                    + " right now. Try again in a moment");
        }
        try {
            return work.get();
        } finally {
            release(bookId, lockId);
        }
    }

    private void release(UUID bookId, UUID lockId) {
        DeleteOptions onlyOurLock = DeleteOptions.builder()
                // The column name, not the property name: Spring passes IF conditions through untranslated
                .ifCondition(Criteria.where("lock_id").is(lockId))
                .build();
        try {
            cassandra.delete(new BookLock(bookId, lockId), onlyOurLock);
        } catch (RuntimeException e) {
            // Not worth failing the request over: the TTL clears the lock in a few seconds anyway
            log.warn("Couldn't release the lock for book {}, it will expire on its own", bookId, e);
        }
    }
}
