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
import java.util.function.Function;

/**
 * A short lock per book, so deleting a book can't overlap with a copy being added to it or put back from
 * withdrawn. Without it, a delete could check "every copy is withdrawn", a copy could be added right after, and
 * the delete would go ahead and leave a copy on the shelf of a book that no longer exists.
 *
 * <p>The lock is a row inserted with IF NOT EXISTS and a TTL, released only if it's still ours. If releasing ever
 * fails, the TTL clears it within a few seconds, and the lock holds no data that could end up wrong. The flip side
 * of the TTL is that a request that stalls can lose the lock without knowing, which is what {@link BookLockLease}
 * is for.
 */
@Component
public class BookLocks {

    private static final Logger log = LoggerFactory.getLogger(BookLocks.class);

    // Far longer than any of the guarded operations takes, and short enough that a leftover lock barely matters
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final InsertOptions TAKE_LOCK = InsertOptions.builder().withIfNotExists().ttl(LOCK_TTL).build();
    // Half the TTL, so a write that starts in time still has a good margin before the lock row goes away
    private static final Duration SAFE_FOR = LOCK_TTL.dividedBy(2);

    private final CassandraOperations cassandra;

    public BookLocks(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /**
     * Runs {@code work} while holding the book's lock. If someone else holds it, nothing runs and the request is
     * refused with a conflict, since retrying a moment later will work. {@code work} gets the lease, and should
     * call {@link BookLockLease#ensureStillHeld()} right before the write that decides the outcome.
     */
    public <T> T whileLocked(UUID bookId, Function<BookLockLease, T> work) {
        UUID lockId = UUID.randomUUID();
        // Taken before the insert, so the lease can only be shorter than the lock, never longer
        long takenAt = System.nanoTime();
        if (!cassandra.insert(new BookLock(bookId, lockId), TAKE_LOCK).wasApplied()) {
            throw new ConflictException("Something else is being done to book " + bookId
                    + " right now. Try again in a moment");
        }
        try {
            return work.apply(new BookLockLease(bookId, takenAt + SAFE_FOR.toNanos(), System::nanoTime));
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
