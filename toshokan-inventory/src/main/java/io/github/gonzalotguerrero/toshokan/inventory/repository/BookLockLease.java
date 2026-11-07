package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Our hold on a book's lock (see {@link BookLocks}). The lock row expires on its own, so if a request stalls long
 * enough, someone else can take the lock while we still think we have it. Checking the lease right before the
 * write that matters makes that much less likely. It can't rule it out: a pause between the check and the write,
 * or a write that Cassandra itself takes long to apply, can still go past the expiry.
 */
public class BookLockLease {

    private final UUID bookId;
    private final Instant safeUntil;
    private final Clock clock;

    /**
     * @param safeUntil the last moment a write may start, well before the lock row actually expires
     */
    public BookLockLease(UUID bookId, Instant safeUntil, Clock clock) {
        this.bookId = bookId;
        this.safeUntil = safeUntil;
        this.clock = clock;
    }

    /**
     * Throws a conflict if the lock has been held for too long to be trusted, so the caller stops before writing
     * anything. Trying again is safe.
     */
    public void ensureStillHeld() {
        if (clock.instant().isAfter(safeUntil)) {
            throw new ConflictException("Changing book " + bookId + " took too long and was stopped before it was"
                    + " saved. Try again");
        }
    }
}
