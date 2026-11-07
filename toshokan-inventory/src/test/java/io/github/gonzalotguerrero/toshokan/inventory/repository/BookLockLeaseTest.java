package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookLockLeaseTest {

    private static final Instant NOON = Instant.parse("2026-06-15T12:00:00Z");
    private static final UUID BOOK_ID = UUID.randomUUID();

    @Test
    void isFineUpToTheMomentItIsSafeUntil() {
        BookLockLease lease = new BookLockLease(BOOK_ID, NOON, Clock.fixed(NOON, ZoneOffset.UTC));

        assertThatCode(lease::ensureStillHeld).doesNotThrowAnyException();
    }

    @Test
    void refusesOnceThatMomentHasPassed() {
        BookLockLease lease = new BookLockLease(BOOK_ID, NOON, Clock.fixed(NOON.plusMillis(1), ZoneOffset.UTC));

        assertThatThrownBy(lease::ensureStillHeld)
                .isInstanceOf(ConflictException.class)
                .hasMessage("Changing book " + BOOK_ID + " took too long and was stopped before it was saved. Try again");
    }
}
