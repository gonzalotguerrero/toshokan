package io.github.gonzalotguerrero.toshokan.shared.cassandra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Decides when a claim on a unique number (an ISBN, an inventory number, a card number) can be taken over by
 * someone else.
 *
 * <p>A claim is written just before the thing that owns it, so for a moment every claim points at something that
 * doesn't exist yet. If saving the owner fails, the claim may be left behind for good (see {@link WriteOutcomes}).
 * A claim is only stale when its owner doesn't exist <em>and</em> it's old enough that the owner can't still be
 * on its way. A timed-out write can take a few seconds to be applied, so ten minutes leaves plenty of room.
 */
public final class StaleClaims {

    public static final Duration MIN_AGE = Duration.ofMinutes(10);

    private StaleClaims() {
    }

    /**
     * @param claimedAt   when Cassandra stored the claim
     * @param ownerExists whether the book, copy or member the claim points at exists
     */
    public static boolean isStale(Instant claimedAt, boolean ownerExists, Clock clock) {
        return !ownerExists && claimedAt.isBefore(clock.instant().minus(MIN_AGE));
    }
}
