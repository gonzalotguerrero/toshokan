package io.github.gonzalotguerrero.toshokan.shared.cassandra;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class StaleClaimsTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void anOldClaimWhoseOwnerIsMissingIsStale() {
        assertThat(StaleClaims.isStale(NOW.minus(Duration.ofMinutes(11)), false, CLOCK)).isTrue();
    }

    @Test
    void aYoungClaimIsNeverStaleSinceItsOwnerMayStillBeComing() {
        assertThat(StaleClaims.isStale(NOW.minus(Duration.ofMinutes(9)), false, CLOCK)).isFalse();
    }

    @Test
    void aClaimWhoseOwnerExistsIsNeverStale() {
        assertThat(StaleClaims.isStale(NOW.minus(Duration.ofDays(365)), true, CLOCK)).isFalse();
    }
}
