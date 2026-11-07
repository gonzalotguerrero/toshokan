package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByMember;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanByMemberRepository;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyLoan;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberLendingState;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.mapping.BasicMapId;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanSlotsTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final UUID MEMBER_ID = UUID.randomUUID();

    @Mock
    private LoanByMemberRepository history;

    private LoanSlots loanSlots;

    @BeforeEach
    void setUp() {
        loanSlots = new LoanSlots(history, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aRecentSlotWithNoCopyAndNoReturnIsPending() {
        UUID loanId = UUID.randomUUID();
        Instant reservedAt = NOW.minusSeconds(5);
        when(history.findById(any())).thenReturn(Optional.empty());

        assertThat(loanSlots.pending(memberWith(Map.of(loanId, reservedAt)), List.of()))
                .isEqualTo(Map.of(loanId, reservedAt));
    }

    @Test
    void aSlotWhoseLoanIsOnACopyIsNotCountedTwice() {
        UUID loanId = UUID.randomUUID();

        assertThat(loanSlots.pending(memberWith(Map.of(loanId, NOW.minusSeconds(5))), List.of(lentCopy(loanId))))
                .isEmpty();
        verifyNoInteractions(history);
    }

    @Test
    void aSlotWhoseLoanWasReturnedIsNotPending() {
        UUID loanId = UUID.randomUUID();
        Instant reservedAt = NOW.minusSeconds(60);
        when(history.findById(BasicMapId.id("memberId", MEMBER_ID).with("lentAt", reservedAt).with("loanId", loanId)))
                .thenReturn(Optional.of(new LoanByMember(MEMBER_ID, reservedAt, loanId, UUID.randomUUID(),
                        UUID.randomUUID(), "LIB-000001", LocalDate.of(2026, 6, 29), 0, NOW.minusSeconds(10))));

        assertThat(loanSlots.pending(memberWith(Map.of(loanId, reservedAt)), List.of())).isEmpty();
    }

    @Test
    void aSlotWithAHistoryRowButNoReturnIsStillPending() {
        // The lend wrote its history and is about to lend the copy
        UUID loanId = UUID.randomUUID();
        Instant reservedAt = NOW.minusSeconds(1);
        when(history.findById(any())).thenReturn(Optional.of(new LoanByMember(MEMBER_ID, reservedAt, loanId,
                UUID.randomUUID(), UUID.randomUUID(), "LIB-000001", LocalDate.of(2026, 6, 29), 0, null)));

        assertThat(loanSlots.pending(memberWith(Map.of(loanId, reservedAt)), List.of())).containsOnlyKeys(loanId);
    }

    @Test
    void aSlotOlderThanThePendingWindowNoLongerCounts() {
        UUID loanId = UUID.randomUUID();
        Instant reservedAt = NOW.minus(LoanSlots.PENDING_FOR).minusMillis(1);

        assertThat(loanSlots.pending(memberWith(Map.of(loanId, reservedAt)), List.of())).isEmpty();
        verifyNoInteractions(history);
    }

    @Test
    void aMemberWithoutSlotsHasNothingPending() {
        assertThat(loanSlots.pending(memberWith(Map.of()), List.of(lentCopy(UUID.randomUUID())))).isEmpty();
    }

    private static MemberLendingState memberWith(Map<UUID, Instant> slots) {
        return new MemberLendingState(MEMBER_ID, "CARD-0001", MemberStatus.ACTIVE, 2L, slots);
    }

    private static BookCopyDetails lentCopy(UUID loanId) {
        return new BookCopyDetails(UUID.randomUUID(), UUID.randomUUID(), "LIB-000002", BookCopyStatus.LENT,
                NOW.minusSeconds(3600), new BookCopyLoan(loanId, MEMBER_ID, NOW.minusSeconds(5),
                LocalDate.of(2026, 6, 29), 0, false));
    }
}
