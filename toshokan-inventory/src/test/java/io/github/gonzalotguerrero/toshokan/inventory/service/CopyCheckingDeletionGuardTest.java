package io.github.gonzalotguerrero.toshokan.inventory.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyRepository;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookLockLease;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookLocks;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopyCheckingDeletionGuardTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    private static final BookDetails DUNE = new BookDetails(UUID.randomUUID(), null, "Dune",
            List.of("Frank Herbert"), null, 1965, "English", Set.of("Science fiction"), Instant.now());

    @Mock
    private BookLocks locks;

    @Mock
    private BookCopyRepository copies;

    @Mock
    private BookHoldingsRefresher holdingsRefresher;

    @InjectMocks
    private CopyCheckingDeletionGuard guard;

    private final AtomicBoolean deleted = new AtomicBoolean();

    @Test
    void letsABookWithoutCopiesBeDeleted() {
        givenTheLockIsHeldFor(Instant.parse("2026-06-15T12:00:05Z"));
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of());

        guard.runGuarded(DUNE, () -> deleted.set(true));

        assertThat(deleted).isTrue();
        verify(holdingsRefresher).refresh(DUNE);
    }

    @Test
    void letsABookWhoseCopiesAreAllWithdrawnBeDeleted() {
        givenTheLockIsHeldFor(Instant.parse("2026-06-15T12:00:05Z"));
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of(
                copy(BookCopyStatus.WITHDRAWN), copy(BookCopyStatus.WITHDRAWN)));

        guard.runGuarded(DUNE, () -> deleted.set(true));

        assertThat(deleted).isTrue();
    }

    @Test
    void refusesWhileAnyCopyIsNotWithdrawn() {
        givenTheLockIsHeldFor(Instant.parse("2026-06-15T12:00:05Z"));
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of(
                copy(BookCopyStatus.WITHDRAWN), copy(BookCopyStatus.DAMAGED), copy(BookCopyStatus.LENT)));

        assertThatThrownBy(() -> guard.runGuarded(DUNE, () -> deleted.set(true)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Book " + DUNE.bookId() + " still has 2 copies that aren't withdrawn. They have to be "
                        + "withdrawn before the book can be deleted");
        assertThat(deleted).isFalse();
        verifyNoInteractions(holdingsRefresher);
    }

    @Test
    void deletesNothingIfTheLockWasHeldTooLong() {
        givenTheLockIsHeldFor(Instant.parse("2026-06-15T11:59:59Z"));
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of());

        assertThatThrownBy(() -> guard.runGuarded(DUNE, () -> deleted.set(true)))
                .isInstanceOf(ConflictException.class);
        assertThat(deleted).isFalse();
        verifyNoInteractions(holdingsRefresher);
    }

    @Test
    void theDeletionStandsEvenIfTheHoldingsCannotBeUpdated() {
        givenTheLockIsHeldFor(Instant.parse("2026-06-15T12:00:05Z"));
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of());
        doThrow(new IllegalStateException("Cassandra is down")).when(holdingsRefresher).refresh(DUNE);

        guard.runGuarded(DUNE, () -> deleted.set(true));

        assertThat(deleted).isTrue();
    }

    /** Runs the work with a lease that's safe until {@code safeUntil}. The clock is fixed at noon. */
    private void givenTheLockIsHeldFor(Instant safeUntil) {
        when(locks.whileLocked(eq(DUNE.bookId()), any())).thenAnswer(call ->
                call.<Function<BookLockLease, ?>>getArgument(1).apply(new BookLockLease(DUNE.bookId(), safeUntil, CLOCK)));
    }

    private static BookCopy copy(BookCopyStatus status) {
        return new BookCopy(DUNE.bookId(), UUID.randomUUID(), "LIB-" + UUID.randomUUID(), status, Instant.now(),
                null, null, null, null, null);
    }
}
