package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanHistoryEntry;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByCopy;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByMember;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanByCopyRepository;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanByMemberRepository;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyLoan;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.members.service.MemberService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.SliceImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanHistoryServiceTest {

    // A library in Tokyo, so "the day it came back" depends on the zone, not on UTC
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), TOKYO);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static final UUID BOOK_ID = UUID.randomUUID();
    private static final UUID COPY_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();

    @Mock
    private LoanByCopyRepository byCopy;

    @Mock
    private LoanByMemberRepository byMember;

    @Mock
    private BookCopyService copies;

    @Mock
    private MemberService members;

    private LoanHistoryService service;

    @BeforeEach
    void setUp() {
        service = new LoanHistoryService(byCopy, byMember, copies, members, CLOCK);
    }

    @Test
    void theCopysCurrentLoanIsActiveAndTakesItsDueDateAndRenewalsFromTheCopy() {
        LoanByCopy row = row(UUID.randomUUID(), Instant.parse("2026-06-01T01:00:00Z"), LocalDate.of(2026, 6, 15), 0,
                null);
        // Renewed twice since the row was written, and now past due
        givenCopyWithLoan(row.loanId(), LocalDate.of(2026, 6, 10), 2);
        givenCopyRows(row);

        LoanHistoryEntry entry = service.copyHistory(BOOK_ID, COPY_ID, 20, null).items().getFirst();

        assertThat(entry.dueDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(entry.renewals()).isEqualTo(2);
        assertThat(entry.overdue()).isTrue();
        assertThat(entry.returnedAt()).isNull();
        assertThat(entry.returnedLate()).isNull();
    }

    @Test
    void aReturnThatDidNotGoThroughStillShowsTheLoanAsActive() {
        // The return was written to the history, but the copy was never put back on the shelf
        LoanByCopy row = row(UUID.randomUUID(), Instant.parse("2026-06-01T01:00:00Z"), LocalDate.of(2026, 6, 20), 0,
                Instant.parse("2026-06-14T05:00:00Z"));
        givenCopyWithLoan(row.loanId(), LocalDate.of(2026, 6, 20), 0);
        givenCopyRows(row);

        LoanHistoryEntry entry = service.copyHistory(BOOK_ID, COPY_ID, 20, null).items().getFirst();

        assertThat(entry.returnedAt()).isNull();
        assertThat(entry.overdue()).isFalse();
    }

    @Test
    void aRowWithAReturnDateIsAReturnedLoan() {
        LoanByCopy row = row(UUID.randomUUID(), Instant.parse("2026-05-01T01:00:00Z"), LocalDate.of(2026, 5, 15), 1,
                Instant.parse("2026-05-14T05:00:00Z"));
        givenCopyOnTheShelf();
        givenCopyRows(row);

        LoanHistoryEntry entry = service.copyHistory(BOOK_ID, COPY_ID, 20, null).items().getFirst();

        assertThat(entry.returnedAt()).isEqualTo(Instant.parse("2026-05-14T05:00:00Z"));
        assertThat(entry.returnedLate()).isFalse();
        assertThat(entry.overdue()).isFalse();
        assertThat(entry.renewals()).isEqualTo(1);
    }

    @Test
    void lateReturnsAreJudgedByTheDateInTheLibrarysZone() {
        // 16:00 UTC on the 15th is already the 16th in Tokyo, one day after the due date
        LoanByCopy row = row(UUID.randomUUID(), Instant.parse("2026-05-01T01:00:00Z"), LocalDate.of(2026, 5, 15), 0,
                Instant.parse("2026-05-15T16:00:00Z"));
        givenCopyOnTheShelf();
        givenCopyRows(row);

        assertThat(service.copyHistory(BOOK_ID, COPY_ID, 20, null).items().getFirst().returnedLate()).isTrue();
    }

    @Test
    void aLendThatNeverWentThroughIsLeftOut() {
        LoanByCopy returned = row(UUID.randomUUID(), Instant.parse("2026-05-01T01:00:00Z"), LocalDate.of(2026, 5, 15),
                0, Instant.parse("2026-05-10T05:00:00Z"));
        // Written to the history, but the copy was never lent
        LoanByCopy orphan = row(UUID.randomUUID(), Instant.parse("2026-06-01T01:00:00Z"), LocalDate.of(2026, 6, 15), 0,
                null);
        givenCopyOnTheShelf();
        givenCopyRows(orphan, returned);

        PageResponse<LoanHistoryEntry> page = service.copyHistory(BOOK_ID, COPY_ID, 20, null);

        assertThat(page.items()).extracting(LoanHistoryEntry::loanId).containsExactly(returned.loanId());
    }

    @Test
    void anUnknownCopyHasNoHistory() {
        when(copies.findBookCopy(BOOK_ID, COPY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.copyHistory(BOOK_ID, COPY_ID, 20, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Book " + BOOK_ID + " has no copy with id " + COPY_ID);
        verifyNoInteractions(byCopy);
    }

    @Test
    void refusesAPageSizeOutOfRange() {
        givenCopyOnTheShelf();

        assertThatThrownBy(() -> service.copyHistory(BOOK_ID, COPY_ID, 101, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("pageSize must be between 1 and 100");
    }

    @Test
    void aMembersHistoryLooksUpEachCopyOnlyOnce() {
        LoanByCopy active = row(UUID.randomUUID(), Instant.parse("2026-06-10T01:00:00Z"), LocalDate.of(2026, 6, 24), 0,
                null);
        LoanByCopy earlier = row(UUID.randomUUID(), Instant.parse("2026-05-01T01:00:00Z"), LocalDate.of(2026, 5, 15),
                0, Instant.parse("2026-05-10T05:00:00Z"));
        givenCopyWithLoan(active.loanId(), LocalDate.of(2026, 6, 24), 0);
        when(byMember.findByMemberId(eq(MEMBER_ID), any()))
                .thenReturn(new SliceImpl<>(List.of(LoanByMember.of(active), LoanByMember.of(earlier))));

        PageResponse<LoanHistoryEntry> page = service.memberHistory(MEMBER_ID, 20, null);

        assertThat(page.items()).extracting(LoanHistoryEntry::loanId)
                .containsExactly(active.loanId(), earlier.loanId());
        assertThat(page.items()).extracting(LoanHistoryEntry::returnedAt)
                .containsExactly(null, Instant.parse("2026-05-10T05:00:00Z"));
        assertThat(page.nextPageToken()).isNull();
        verify(copies).findBookCopy(BOOK_ID, COPY_ID);
    }

    @Test
    void aReturnedLoanStillShowsUpAfterItsCopyIsGone() {
        LoanByCopy returned = row(UUID.randomUUID(), Instant.parse("2026-05-01T01:00:00Z"), LocalDate.of(2026, 5, 15),
                0, Instant.parse("2026-05-10T05:00:00Z"));
        when(copies.findBookCopy(BOOK_ID, COPY_ID)).thenReturn(Optional.empty());
        when(byMember.findByMemberId(eq(MEMBER_ID), any()))
                .thenReturn(new SliceImpl<>(List.of(LoanByMember.of(returned))));

        assertThat(service.memberHistory(MEMBER_ID, 20, null).items()).hasSize(1);
    }

    @Test
    void anUnknownMemberHasNoHistory() {
        when(members.getMember(MEMBER_ID)).thenThrow(new NotFoundException("No member with id " + MEMBER_ID));

        assertThatThrownBy(() -> service.memberHistory(MEMBER_ID, 20, null)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(byMember);
    }

    private void givenCopyRows(LoanByCopy... rows) {
        when(byCopy.findByBookCopyId(eq(COPY_ID), any())).thenReturn(new SliceImpl<>(List.of(rows)));
    }

    private void givenCopyOnTheShelf() {
        when(copies.findBookCopy(BOOK_ID, COPY_ID)).thenReturn(Optional.of(new BookCopyDetails(COPY_ID, BOOK_ID,
                "LIB-000001", BookCopyStatus.AVAILABLE, Instant.parse("2026-01-01T00:00:00Z"), null)));
    }

    private void givenCopyWithLoan(UUID loanId, LocalDate dueDate, int renewals) {
        BookCopyLoan loan = new BookCopyLoan(loanId, MEMBER_ID, Instant.parse("2026-06-01T01:00:00Z"), dueDate,
                renewals, TODAY.isAfter(dueDate));
        when(copies.findBookCopy(BOOK_ID, COPY_ID)).thenReturn(Optional.of(new BookCopyDetails(COPY_ID, BOOK_ID,
                "LIB-000001", BookCopyStatus.LENT, Instant.parse("2026-01-01T00:00:00Z"), loan)));
    }

    private static LoanByCopy row(UUID loanId, Instant lentAt, LocalDate dueDate, int renewals, Instant returnedAt) {
        return new LoanByCopy(COPY_ID, lentAt, loanId, BOOK_ID, "LIB-000001", MEMBER_ID, dueDate, renewals,
                returnedAt);
    }
}
