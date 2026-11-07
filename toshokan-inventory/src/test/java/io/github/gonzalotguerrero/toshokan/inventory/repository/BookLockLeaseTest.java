package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookLockLeaseTest {

    private static final UUID BOOK_ID = UUID.randomUUID();

    @Test
    void isFineUpToTheMomentItIsSafeUntil() {
        BookLockLease lease = new BookLockLease(BOOK_ID, 1_000, () -> 1_000);

        assertThatCode(lease::ensureStillHeld).doesNotThrowAnyException();
    }

    @Test
    void refusesOnceThatMomentHasPassed() {
        BookLockLease lease = new BookLockLease(BOOK_ID, 1_000, () -> 1_001);

        assertThatThrownBy(lease::ensureStillHeld)
                .isInstanceOf(ConflictException.class)
                .hasMessage("Changing book " + BOOK_ID + " took too long and was stopped before it was saved. Try again");
    }

    @Test
    void copesWithTheMonotonicClockWrappingAround() {
        // nanoTime can be any long, including near the top of the range, so the comparison has to survive overflow
        BookLockLease lease = new BookLockLease(BOOK_ID, Long.MAX_VALUE, () -> Long.MAX_VALUE + 10);

        assertThatThrownBy(lease::ensureStillHeld).isInstanceOf(ConflictException.class);
    }
}
