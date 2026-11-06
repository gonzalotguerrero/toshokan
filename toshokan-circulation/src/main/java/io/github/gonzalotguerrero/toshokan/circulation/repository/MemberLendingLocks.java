package io.github.gonzalotguerrero.toshokan.circulation.repository;

import io.github.gonzalotguerrero.toshokan.circulation.model.MemberLendingLock;
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
 * A short lock per member, so only one lend (or deactivation) for the same member runs at a time.
 *
 * <p>Without it, two lends to a member with 2 books out could both see "2, under the limit of 3" and both go
 * through, leaving them with 4. The copy's own conditional write can't stop that, because the two lends touch
 * different copies.
 *
 * <p>The lock is a row inserted with IF NOT EXISTS and a TTL. If releasing it ever fails, Cassandra drops the row
 * by itself once the TTL is up, so the worst case is a member who can't borrow for a few seconds. The lock never
 * holds any data that could end up wrong. Releasing is conditional on our own lock id, so we can't remove a lock
 * someone else took after ours expired.
 */
@Component
public class MemberLendingLocks {

    private static final Logger log = LoggerFactory.getLogger(MemberLendingLocks.class);

    // Far longer than a lend takes, and short enough that a lock left behind doesn't get in anyone's way for long
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final InsertOptions TAKE_LOCK = InsertOptions.builder().withIfNotExists().ttl(LOCK_TTL).build();

    private final CassandraOperations cassandra;

    public MemberLendingLocks(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /**
     * Runs {@code work} while holding the member's lock. If someone else holds it, nothing runs and the request is
     * refused with a conflict, since retrying a moment later will work.
     */
    public <T> T whileLocked(UUID memberId, Supplier<T> work) {
        UUID lockId = UUID.randomUUID();
        if (!cassandra.insert(new MemberLendingLock(memberId, lockId), TAKE_LOCK).wasApplied()) {
            throw new ConflictException("Something else is being done for member " + memberId
                    + " right now. Try again in a moment");
        }
        try {
            return work.get();
        } finally {
            release(memberId, lockId);
        }
    }

    private void release(UUID memberId, UUID lockId) {
        DeleteOptions onlyOurLock = DeleteOptions.builder()
                // The column name, not the property name: Spring passes IF conditions through untranslated
                .ifCondition(Criteria.where("lock_id").is(lockId))
                .build();
        try {
            cassandra.delete(new MemberLendingLock(memberId, lockId), onlyOurLock);
        } catch (RuntimeException e) {
            // Not worth failing the request over: the TTL clears the lock in a few seconds anyway
            log.warn("Couldn't release the lending lock for member {}, it will expire on its own", memberId, e);
        }
    }
}
