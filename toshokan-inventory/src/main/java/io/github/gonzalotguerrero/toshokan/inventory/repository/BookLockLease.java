package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;

import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Our hold on a book's lock (see {@link BookLocks}). The lock row expires on its own, so if a request stalls long
 * enough, someone else can take the lock while we still think we have it. Checking the lease right before the
 * write that matters makes that much less likely. It can't rule it out: a pause between the check and the write,
 * or a write that Cassandra itself takes long to apply, can still go past the expiry.
 *
 * <p>Time is measured with a monotonic clock ({@link System#nanoTime()} in the app), not the time of day, so the
 * server's clock being set back can't make the lease look younger than it is.
 */
public class BookLockLease {

    private final UUID bookId;
    private final long safeUntilNanos;
    private final LongSupplier nanoTime;

    /**
     * @param safeUntilNanos the last moment a write may start, as a {@code nanoTime} reading, well before the lock
     *                       row actually expires
     * @param nanoTime       the monotonic clock {@code safeUntilNanos} was read from
     */
    public BookLockLease(UUID bookId, long safeUntilNanos, LongSupplier nanoTime) {
        this.bookId = bookId;
        this.safeUntilNanos = safeUntilNanos;
        this.nanoTime = nanoTime;
    }

    /**
     * Throws a conflict if the lock has been held for too long to be trusted, so the caller stops before writing
     * anything. Trying again is safe.
     */
    public void ensureStillHeld() {
        // Subtracting first is how nanoTime readings have to be compared, since they can wrap around
        if (nanoTime.getAsLong() - safeUntilNanos > 0) {
            throw new ConflictException("Changing book " + bookId + " took too long and was stopped before it was"
                    + " saved. Try again");
        }
    }
}
