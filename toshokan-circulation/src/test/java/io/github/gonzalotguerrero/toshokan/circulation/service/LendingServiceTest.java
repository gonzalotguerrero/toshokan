package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.service.BookService;
import io.github.gonzalotguerrero.toshokan.circulation.config.LendingProperties;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LendRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanDetails;
import io.github.gonzalotguerrero.toshokan.circulation.dto.OverdueLoan;
import io.github.gonzalotguerrero.toshokan.circulation.dto.RenewalRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.ReturnRequest;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByCopy;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanHistoryWriter;
import io.github.gonzalotguerrero.toshokan.circulation.repository.MemberLendingLocks;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyLoan;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import io.github.gonzalotguerrero.toshokan.members.service.MemberService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LendingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final LendingProperties RULES = new LendingProperties(Period.ofDays(14), 2, 3);

    private static final MemberDetails ANA = new MemberDetails(UUID.randomUUID(), "CARD-0001", "Ana Pérez",
            "ana@example.com", null, MemberStatus.ACTIVE, Instant.parse("2026-01-10T09:00:00Z"));
    private static final BookCopyDetails ON_SHELF = new BookCopyDetails(UUID.randomUUID(), UUID.randomUUID(),
            "LIB-000001", BookCopyStatus.AVAILABLE, Instant.parse("2026-02-01T09:00:00Z"), null);

    @Mock
    private MemberService members;

    @Mock
    private BookService books;

    @Mock
    private BookCopyService copies;

    @Mock
    private MemberLendingLocks locks;

    @Mock
    private LoanHistoryWriter history;

    private LendingService service;

    @BeforeEach
    void setUp() {
        service = new LendingService(members, books, copies, locks, history, RULES, CLOCK);
    }

    @Test
    void lendsACopyForTheLoanPeriod() {
        givenMemberAndCopy();
        givenTheLockIsFree();
        when(copies.findCopiesLentTo(ANA.memberId())).thenReturn(List.of());
        when(copies.markLent(eq(ON_SHELF.bookId()), eq(ON_SHELF.bookCopyId()), eq(ANA.memberId()), any(),
                eq(CLOCK.instant()), eq(LocalDate.of(2026, 6, 29))))
                .thenReturn(lentCopy("LIB-000001", LocalDate.of(2026, 6, 29), 0));

        LoanDetails loan = service.lend(new LendRequest(" LIB-000001 ", " CARD-0001 "));

        assertThat(loan.dueDate()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(loan.memberId()).isEqualTo(ANA.memberId());
        verify(locks).whileLocked(eq(ANA.memberId()), any());
    }

    @Test
    void recordsTheLoanInTheHistoryBeforeLendingTheCopy() {
        givenMemberAndCopy();
        givenTheLockIsFree();
        when(copies.findCopiesLentTo(ANA.memberId())).thenReturn(List.of());
        ArgumentCaptor<UUID> loanId = ArgumentCaptor.forClass(UUID.class);
        when(copies.markLent(any(), any(), any(), loanId.capture(), any(), any()))
                .thenReturn(lentCopy("LIB-000001", LocalDate.of(2026, 6, 29), 0));

        service.lend(new LendRequest("LIB-000001", "CARD-0001"));

        InOrder order = inOrder(history, copies);
        ArgumentCaptor<LoanByCopy> saved = ArgumentCaptor.forClass(LoanByCopy.class);
        order.verify(history).save(saved.capture());
        order.verify(copies).markLent(any(), any(), any(), any(), any(), any());
        // The history row and the copy must name the same loan, or the row would be read as a failed lend
        assertThat(saved.getValue()).isEqualTo(new LoanByCopy(ON_SHELF.bookCopyId(), CLOCK.instant(),
                loanId.getValue(), ON_SHELF.bookId(), "LIB-000001", ANA.memberId(), LocalDate.of(2026, 6, 29), 0,
                null));
    }

    @Test
    void answersAnUnknownCardWithNotFound() {
        when(members.findMemberByCardNumber("CARD-9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lend(new LendRequest("LIB-000001", "CARD-9999")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No member with card number CARD-9999");
        verifyNoInteractions(locks);
    }

    @Test
    void answersAnUnknownInventoryNumberWithNotFound() {
        when(members.findMemberByCardNumber("CARD-0001")).thenReturn(Optional.of(ANA));
        when(copies.findBookCopyByInventoryNumber("LIB-9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lend(new LendRequest("LIB-9999", "CARD-0001")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No book copy with inventory number LIB-9999");
        verifyNoInteractions(locks);
    }

    @Test
    void refusesToLendToAnInactiveMember() {
        givenMemberAndCopy();
        givenTheLockIsFree();
        when(members.getMember(ANA.memberId())).thenReturn(withStatus(MemberStatus.INACTIVE));

        assertThatThrownBy(() -> service.lend(new LendRequest("LIB-000001", "CARD-0001")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Member CARD-0001 is inactive and can't borrow books");
        verify(copies, never()).markLent(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(history);
    }

    @Test
    void refusesToLendToAMemberWithAnOverdueBook() {
        givenMemberAndCopy();
        givenTheLockIsFree();
        when(copies.findCopiesLentTo(ANA.memberId()))
                .thenReturn(List.of(lentCopy("LIB-000007", TODAY.minusDays(1), 0)));

        assertThatThrownBy(() -> service.lend(new LendRequest("LIB-000001", "CARD-0001")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Member CARD-0001 has an overdue book (LIB-000007) and can't borrow more until it's returned");
        verify(copies, never()).markLent(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(history);
    }

    @Test
    void refusesToLendBeyondTheLimit() {
        givenMemberAndCopy();
        givenTheLockIsFree();
        when(copies.findCopiesLentTo(ANA.memberId())).thenReturn(List.of(
                lentCopy("LIB-000002", TODAY.plusDays(3), 0),
                lentCopy("LIB-000003", TODAY.plusDays(5), 0),
                lentCopy("LIB-000004", TODAY.plusDays(9), 1)));

        assertThatThrownBy(() -> service.lend(new LendRequest("LIB-000001", "CARD-0001")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Member CARD-0001 already has 3 books out, which is the most allowed");
        verify(copies, never()).markLent(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(history);
    }

    @Test
    void returnsALentCopy() {
        BookCopyDetails lent = lentCopy("LIB-000001", TODAY.minusDays(4), 0);
        when(copies.findBookCopyByInventoryNumber("LIB-000001")).thenReturn(Optional.of(lent));

        service.returnCopy(new ReturnRequest("LIB-000001"));

        // Overdue or not, the book can always come back
        verify(copies).markReturned(lent.bookId(), lent.bookCopyId(), lent.loan().loanId(), 0);
    }

    @Test
    void recordsTheReturnInTheHistoryBeforeReturningTheCopy() {
        BookCopyDetails lent = lentCopy("LIB-000001", TODAY.plusDays(4), 1);
        when(copies.findBookCopyByInventoryNumber("LIB-000001")).thenReturn(Optional.of(lent));

        service.returnCopy(new ReturnRequest("LIB-000001"));

        InOrder order = inOrder(history, copies);
        BookCopyLoan loan = lent.loan();
        order.verify(history).save(new LoanByCopy(lent.bookCopyId(), loan.lentAt(), loan.loanId(), lent.bookId(),
                "LIB-000001", ANA.memberId(), TODAY.plusDays(4), 1, CLOCK.instant()));
        order.verify(copies).markReturned(lent.bookId(), lent.bookCopyId(), loan.loanId(), 1);
    }

    @Test
    void refusesToReturnACopyThatIsNotLentOut() {
        when(copies.findBookCopyByInventoryNumber("LIB-000001")).thenReturn(Optional.of(ON_SHELF));

        assertThatThrownBy(() -> service.returnCopy(new ReturnRequest("LIB-000001")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Book copy LIB-000001 isn't lent out");
        verifyNoInteractions(history);
    }

    @Test
    void renewingSetsTheDueDateToTodayPlusTheLoanPeriod() {
        BookCopyDetails lent = lentCopy("LIB-000001", TODAY.plusDays(2), 1);
        when(copies.findBookCopyByInventoryNumber("LIB-000001")).thenReturn(Optional.of(lent));
        when(copies.extendLoan(lent.bookId(), lent.bookCopyId(), lent.loan().loanId(), 1, LocalDate.of(2026, 6, 29)))
                .thenReturn(lentCopy("LIB-000001", LocalDate.of(2026, 6, 29), 2));

        LoanDetails renewed = service.renew(new RenewalRequest("LIB-000001"));

        assertThat(renewed.dueDate()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(renewed.renewals()).isEqualTo(2);
    }

    @Test
    void refusesToRenewAnOverdueLoan() {
        BookCopyDetails lent = lentCopy("LIB-000001", TODAY.minusDays(1), 0);
        when(copies.findBookCopyByInventoryNumber("LIB-000001")).thenReturn(Optional.of(lent));

        assertThatThrownBy(() -> service.renew(new RenewalRequest("LIB-000001")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Book copy LIB-000001 is overdue, so it can't be renewed. It has to be returned first");
        verify(copies, never()).extendLoan(any(), any(), any(), anyInt(), any());
    }

    @Test
    void refusesARenewalPastTheLimit() {
        BookCopyDetails lent = lentCopy("LIB-000001", TODAY.plusDays(2), 2);
        when(copies.findBookCopyByInventoryNumber("LIB-000001")).thenReturn(Optional.of(lent));

        assertThatThrownBy(() -> service.renew(new RenewalRequest("LIB-000001")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Book copy LIB-000001 has already been renewed 2 times, which is the most allowed");
        verify(copies, never()).extendLoan(any(), any(), any(), anyInt(), any());
    }

    @Test
    void listsAMembersLoansDueSoonestFirst() {
        when(members.getMember(ANA.memberId())).thenReturn(ANA);
        when(copies.findCopiesLentTo(ANA.memberId())).thenReturn(List.of(
                lentCopy("LIB-000002", TODAY.plusDays(9), 0),
                lentCopy("LIB-000003", TODAY.plusDays(3), 0)));

        assertThat(service.memberLoans(ANA.memberId()))
                .extracting(LoanDetails::inventoryNumber)
                .containsExactly("LIB-000003", "LIB-000002");
    }

    @Test
    void listingTheLoansOfAnUnknownMemberFailsWithNotFound() {
        UUID memberId = UUID.randomUUID();
        when(members.getMember(memberId)).thenThrow(new NotFoundException("No member with id " + memberId));

        assertThatThrownBy(() -> service.memberLoans(memberId)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(copies);
    }

    @Test
    void listsOverdueLoansMostOverdueFirstWithTheMembersContactDetails() {
        BookCopyDetails slightlyLate = lentCopy("LIB-000002", TODAY.minusDays(2), 0);
        BookCopyDetails veryLate = lentCopy("LIB-000003", TODAY.minusDays(10), 1);
        BookCopyDetails dueToday = lentCopy("LIB-000004", TODAY, 0);
        when(copies.findLentCopies()).thenReturn(List.of(slightlyLate, veryLate, dueToday));
        when(books.findBook(any())).thenAnswer(call -> Optional.of(book(call.getArgument(0), "Kokoro")));
        when(members.findMember(ANA.memberId())).thenReturn(Optional.of(ANA));

        List<OverdueLoan> overdue = service.overdueLoans();

        // Due today isn't late yet
        assertThat(overdue).extracting(OverdueLoan::inventoryNumber).containsExactly("LIB-000003", "LIB-000002");
        assertThat(overdue).extracting(OverdueLoan::daysOverdue).containsExactly(10L, 2L);
        OverdueLoan first = overdue.getFirst();
        assertThat(first.title()).isEqualTo("Kokoro");
        assertThat(first.cardNumber()).isEqualTo("CARD-0001");
        assertThat(first.fullName()).isEqualTo("Ana Pérez");
        assertThat(first.email()).isEqualTo("ana@example.com");
        assertThat(first.loanId()).isEqualTo(veryLate.loan().loanId());
        // Ana has both late books, but is only looked up once
        verify(members).findMember(ANA.memberId());
    }

    @Test
    void anOverdueLoanStaysOnTheListEvenIfItsBookOrMemberCantBeFound() {
        when(copies.findLentCopies()).thenReturn(List.of(lentCopy("LIB-000002", TODAY.minusDays(3), 0)));
        when(books.findBook(any())).thenReturn(Optional.empty());
        when(members.findMember(ANA.memberId())).thenReturn(Optional.empty());

        assertThat(service.overdueLoans()).singleElement().satisfies(loan -> {
            assertThat(loan.inventoryNumber()).isEqualTo("LIB-000002");
            assertThat(loan.title()).isNull();
            assertThat(loan.cardNumber()).isNull();
            assertThat(loan.daysOverdue()).isEqualTo(3);
        });
    }

    private void givenMemberAndCopy() {
        when(members.findMemberByCardNumber("CARD-0001")).thenReturn(Optional.of(ANA));
        when(copies.findBookCopyByInventoryNumber("LIB-000001")).thenReturn(Optional.of(ON_SHELF));
        // Lending reads the member a second time once it holds the lock
        when(members.getMember(ANA.memberId())).thenReturn(ANA);
    }

    /** Makes the lock behave like nobody else is lending to the member: it just runs the work. */
    private void givenTheLockIsFree() {
        when(locks.whileLocked(eq(ANA.memberId()), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
    }

    private static MemberDetails withStatus(MemberStatus status) {
        return new MemberDetails(ANA.memberId(), ANA.cardNumber(), ANA.fullName(), ANA.email(), ANA.phone(), status,
                ANA.joinedAt());
    }

    private static BookDetails book(UUID bookId, String title) {
        return new BookDetails(bookId, "9780000000000", title, List.of("Natsume Sōseki"), "Iwanami", 1914, "ja",
                Set.of("Novel"), Instant.parse("2026-01-01T09:00:00Z"));
    }

    private static BookCopyDetails lentCopy(String inventoryNumber, LocalDate dueDate, int renewals) {
        BookCopyLoan loan = new BookCopyLoan(UUID.randomUUID(), ANA.memberId(), Instant.parse("2026-06-01T10:00:00Z"),
                dueDate, renewals, TODAY.isAfter(dueDate));
        return new BookCopyDetails(UUID.randomUUID(), UUID.randomUUID(), inventoryNumber, BookCopyStatus.LENT,
                Instant.parse("2026-02-01T09:00:00Z"), loan);
    }
}
