package io.github.gonzalotguerrero.toshokan.inventory.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.service.BookService;
import io.github.gonzalotguerrero.toshokan.inventory.dto.AddBookCopyRequest;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyByInventoryNumber;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyByInventoryNumberRepository;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyRepository;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyStatusChanges;
import io.github.gonzalotguerrero.toshokan.inventory.repository.InventoryNumberClaims;
import io.github.gonzalotguerrero.toshokan.inventory.repository.NewBookCopyInserter;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.mapping.BasicMapId;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookCopyServiceTest {

    // Noon on June 15th in the library's zone, so "today" is the same date whatever the machine's zone is
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static final UUID MEMBER_ID = UUID.randomUUID();

    private static final BookDetails DUNE = new BookDetails(UUID.randomUUID(), null, "Dune",
            List.of("Frank Herbert"), null, 1965, "English", Set.of("Science fiction"), Instant.now());

    @Mock
    private BookService books;

    @Mock
    private BookCopyRepository copies;

    @Mock
    private BookCopyByInventoryNumberRepository copiesByInventoryNumber;

    @Mock
    private InventoryNumberClaims inventoryNumbers;

    @Mock
    private NewBookCopyInserter newCopies;

    @Mock
    private BookCopyStatusChanges statusChanges;

    @Mock
    private BookHoldingsRefresher holdingsRefresher;

    private BookCopyService service;

    @BeforeEach
    void setUp() {
        service = new BookCopyService(books, copies, copiesByInventoryNumber, inventoryNumbers, newCopies,
                statusChanges, holdingsRefresher, CLOCK);
    }

    @Test
    void addsAnAvailableCopyAndRefreshesTheHoldings() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(eq("LIB-000123"), eq(DUNE.bookId()), any())).thenReturn(true);

        BookCopyDetails added = service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("  LIB-000123 "));

        ArgumentCaptor<BookCopy> saved = ArgumentCaptor.forClass(BookCopy.class);
        verify(newCopies).insert(saved.capture());
        assertThat(saved.getValue().bookId()).isEqualTo(DUNE.bookId());
        assertThat(saved.getValue().inventoryNumber()).isEqualTo("LIB-000123");
        assertThat(saved.getValue().status()).isEqualTo(BookCopyStatus.AVAILABLE);
        assertThat(added).isEqualTo(BookCopyDetails.from(saved.getValue(), TODAY));
        verify(inventoryNumbers).claim("LIB-000123", DUNE.bookId(), added.bookCopyId());
        verify(holdingsRefresher).refresh(DUNE);
    }

    @Test
    void failsWithNotFoundForAnUnknownBook() {
        UUID bookId = UUID.randomUUID();
        when(books.findBook(bookId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addBookCopy(bookId, new AddBookCopyRequest("LIB-000123")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No book with id " + bookId);
        verifyNoInteractions(copies, inventoryNumbers, holdingsRefresher);
    }

    @Test
    void rejectsAnInventoryNumberAlreadyInUse() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("LIB-000123")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Inventory number LIB-000123 is already in use");
        verify(newCopies, never()).insert(any(BookCopy.class));
        verifyNoInteractions(holdingsRefresher);
    }

    @Test
    void givesTheInventoryNumberBackIfSavingTheCopyFails() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(anyString(), any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("Cassandra is down")).when(newCopies).insert(any(BookCopy.class));

        assertThatThrownBy(() -> service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("LIB-000123")))
                .isInstanceOf(IllegalStateException.class);
        verify(inventoryNumbers).release(eq("LIB-000123"), eq(DUNE.bookId()), any());
        verifyNoInteractions(holdingsRefresher);
    }

    @Test
    void stillReturnsTheCopyWhenTheHoldingsCannotBeRefreshed() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(anyString(), any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("Cassandra hiccup")).when(holdingsRefresher).refresh(DUNE);

        BookCopyDetails added = service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("LIB-000123"));

        assertThat(added.inventoryNumber()).isEqualTo("LIB-000123");
        verify(inventoryNumbers, never()).release(anyString(), any(), any());
    }

    @Test
    void listsEveryCopySortedByInventoryNumber() {
        BookCopy second = copy("LIB-000002", BookCopyStatus.WITHDRAWN);
        BookCopy first = copy("LIB-000001", BookCopyStatus.AVAILABLE);
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of(second, first));

        assertThat(service.listBookCopies(DUNE.bookId()))
                .containsExactly(BookCopyDetails.from(first, TODAY), BookCopyDetails.from(second, TODAY));
    }

    @Test
    void listingTheCopiesOfAnUnknownBookFailsWithNotFound() {
        UUID bookId = UUID.randomUUID();
        when(books.findBook(bookId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listBookCopies(bookId)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(copies);
    }

    @Test
    void changesTheStatusAndRefreshesTheHoldings() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.AVAILABLE);
        givenExistingCopy(copy);
        when(statusChanges.change(copy, BookCopyStatus.DAMAGED)).thenReturn(true);

        BookCopyDetails changed = service.changeStatus(DUNE.bookId(), copy.bookCopyId(), BookCopyStatus.DAMAGED);

        assertThat(changed.status()).isEqualTo(BookCopyStatus.DAMAGED);
        assertThat(changed.bookCopyId()).isEqualTo(copy.bookCopyId());
        verify(holdingsRefresher).refresh(DUNE);
    }

    @Test
    void settingTheSameStatusAgainWritesNothing() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.DAMAGED);
        givenExistingCopy(copy);

        BookCopyDetails unchanged = service.changeStatus(DUNE.bookId(), copy.bookCopyId(), BookCopyStatus.DAMAGED);

        assertThat(unchanged).isEqualTo(BookCopyDetails.from(copy, TODAY));
        verifyNoInteractions(statusChanges, holdingsRefresher);
    }

    @Test
    void refusesToMarkACopyAsLentByHand() {
        UUID bookCopyId = UUID.randomUUID();

        assertThatThrownBy(() -> service.changeStatus(DUNE.bookId(), bookCopyId, BookCopyStatus.LENT))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A copy only becomes LENT when it's lent to a member");
        verifyNoInteractions(books, copies, statusChanges, holdingsRefresher);
    }

    @Test
    void refusesToChangeACopyThatIsLentOut() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.LENT);
        givenExistingCopy(copy);

        assertThatThrownBy(() -> service.changeStatus(DUNE.bookId(), copy.bookCopyId(), BookCopyStatus.AVAILABLE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Book copy LIB-000001 is lent out. Its status changes back when it's returned");
        verifyNoInteractions(statusChanges, holdingsRefresher);
    }

    @Test
    void reportsAConflictWhenSomeoneElseChangedTheCopyFirst() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.AVAILABLE);
        givenExistingCopy(copy);
        when(statusChanges.change(copy, BookCopyStatus.LOST)).thenReturn(false);

        assertThatThrownBy(() -> service.changeStatus(DUNE.bookId(), copy.bookCopyId(), BookCopyStatus.LOST))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Book copy LIB-000001 was changed by someone else at the same time. Try again");
        verifyNoInteractions(holdingsRefresher);
    }

    @Test
    void failsWithNotFoundForAnUnknownCopy() {
        UUID bookCopyId = UUID.randomUUID();
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(copies.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(DUNE.bookId(), bookCopyId, BookCopyStatus.DAMAGED))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Book " + DUNE.bookId() + " has no copy with id " + bookCopyId);
    }

    @Test
    void withdrawingMarksTheCopyAsWithdrawn() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.DAMAGED);
        givenExistingCopy(copy);
        when(statusChanges.change(copy, BookCopyStatus.WITHDRAWN)).thenReturn(true);

        service.withdraw(DUNE.bookId(), copy.bookCopyId());

        verify(statusChanges).change(copy, BookCopyStatus.WITHDRAWN);
        verify(holdingsRefresher).refresh(DUNE);
        // The number stays taken, so it can't be handed to a new copy and get mixed up with the old record
        verify(inventoryNumbers, never()).release(anyString(), any(), any());
    }

    @Test
    void refusesToWithdrawACopyThatIsLentOut() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.LENT);
        givenExistingCopy(copy);

        assertThatThrownBy(() -> service.withdraw(DUNE.bookId(), copy.bookCopyId()))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(statusChanges, holdingsRefresher);
    }

    @Test
    void findsACopyByItsInventoryNumber() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.AVAILABLE);
        when(copiesByInventoryNumber.findById("LIB-000001"))
                .thenReturn(Optional.of(new BookCopyByInventoryNumber("LIB-000001", DUNE.bookId(), copy.bookCopyId())));
        when(copies.findById(BasicMapId.id("bookId", DUNE.bookId()).with("bookCopyId", copy.bookCopyId())))
                .thenReturn(Optional.of(copy));

        assertThat(service.findBookCopyByInventoryNumber(" LIB-000001 ")).contains(BookCopyDetails.from(copy, TODAY));
    }

    @Test
    void lendingAnAvailableCopyRecordsTheLoanOnIt() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.AVAILABLE);
        givenExistingCopy(copy);
        when(statusChanges.replace(eq(copy), any())).thenReturn(true);
        UUID memberId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        Instant lentAt = Instant.parse("2026-06-15T12:00:00Z");

        BookCopyDetails lent = service.markLent(DUNE.bookId(), copy.bookCopyId(), memberId, loanId, lentAt,
                TODAY.plusDays(14));

        ArgumentCaptor<BookCopy> written = ArgumentCaptor.forClass(BookCopy.class);
        verify(statusChanges).replace(eq(copy), written.capture());
        assertThat(written.getValue().status()).isEqualTo(BookCopyStatus.LENT);
        assertThat(written.getValue().memberId()).isEqualTo(memberId);
        // The loan id and time are the ones circulation already wrote into the loan history
        assertThat(written.getValue().loanId()).isEqualTo(loanId);
        assertThat(written.getValue().lentAt()).isEqualTo(lentAt);
        assertThat(written.getValue().renewals()).isZero();
        assertThat(lent.loan().dueDate()).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(lent.loan().overdue()).isFalse();
        verify(holdingsRefresher).refresh(DUNE);
    }

    @Test
    void refusesToLendACopyThatIsNotAvailable() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.DAMAGED);
        givenExistingCopy(copy);

        assertThatThrownBy(() -> service.markLent(DUNE.bookId(), copy.bookCopyId(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), TODAY))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Book copy LIB-000001 can't be lent because it's DAMAGED");
        verifyNoInteractions(statusChanges, holdingsRefresher);
    }

    @Test
    void reportsAConflictWhenTheCopyIsLentBySomeoneElseAtTheSameTime() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.AVAILABLE);
        givenExistingCopy(copy);
        when(statusChanges.replace(eq(copy), any())).thenReturn(false);

        assertThatThrownBy(() -> service.markLent(DUNE.bookId(), copy.bookCopyId(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), TODAY))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("was changed by someone else at the same time");
        verifyNoInteractions(holdingsRefresher);
    }

    @Test
    void returningClearsTheLoan() {
        BookCopy lent = lentCopy(TODAY.plusDays(3), 0);
        givenExistingCopy(lent);
        when(statusChanges.replace(lent, lent.returned())).thenReturn(true);

        BookCopyDetails returned = service.markReturned(DUNE.bookId(), lent.bookCopyId(), lent.loanId(), 0);

        assertThat(returned.status()).isEqualTo(BookCopyStatus.AVAILABLE);
        assertThat(returned.loan()).isNull();
        verify(holdingsRefresher).refresh(DUNE);
    }

    @Test
    void refusesAReturnForALoanThatIsNoLongerThere() {
        BookCopy lent = lentCopy(TODAY.plusDays(3), 0);
        givenExistingCopy(lent);

        assertThatThrownBy(() -> service.markReturned(DUNE.bookId(), lent.bookCopyId(), UUID.randomUUID(), 0))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(statusChanges);
    }

    @Test
    void refusesAReturnIfTheLoanWasRenewedInTheMeantime() {
        BookCopy lent = lentCopy(TODAY.plusDays(3), 1);
        givenExistingCopy(lent);

        assertThatThrownBy(() -> service.markReturned(DUNE.bookId(), lent.bookCopyId(), lent.loanId(), 0))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("was changed by someone else at the same time");
        verifyNoInteractions(statusChanges);
    }

    @Test
    void findsACopyById() {
        BookCopy lent = lentCopy(TODAY.plusDays(3), 0);
        givenStoredCopy(lent);

        assertThat(service.findBookCopy(DUNE.bookId(), lent.bookCopyId())).contains(BookCopyDetails.from(lent, TODAY));
    }

    @Test
    void findsEveryLentCopyThroughTheStatus() {
        BookCopy first = lentCopy(TODAY.plusDays(3), 0);
        BookCopy second = lentCopy(TODAY.minusDays(2), 0);
        when(copies.findByStatus(BookCopyStatus.LENT)).thenReturn(List.of(first, second));

        assertThat(service.findLentCopies())
                .extracting(copy -> copy.loan().overdue())
                .containsExactly(false, true);
    }

    @Test
    void extendingALoanMovesTheDueDateAndCountsTheRenewal() {
        BookCopy lent = lentCopy(TODAY.plusDays(3), 1);
        givenStoredCopy(lent);
        when(statusChanges.replace(lent, lent.renewedUntil(TODAY.plusDays(14)))).thenReturn(true);

        BookCopyDetails renewed = service.extendLoan(DUNE.bookId(), lent.bookCopyId(), lent.loanId(), 1,
                TODAY.plusDays(14));

        assertThat(renewed.loan().dueDate()).isEqualTo(TODAY.plusDays(14));
        assertThat(renewed.loan().renewals()).isEqualTo(2);
    }

    @Test
    void refusesToExtendALoanThatWasRenewedInTheMeantime() {
        BookCopy lent = lentCopy(TODAY.plusDays(3), 1);
        givenStoredCopy(lent);

        assertThatThrownBy(() -> service.extendLoan(DUNE.bookId(), lent.bookCopyId(), lent.loanId(), 0,
                TODAY.plusDays(14)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(statusChanges);
    }

    @Test
    void aLoanIsOverdueFromTheDayAfterItsDueDate() {
        BookCopy dueToday = lentCopy(TODAY, 0);
        BookCopy dueYesterday = lentCopy(TODAY.minusDays(1), 0);
        when(copies.findByMemberId(dueToday.memberId())).thenReturn(List.of(dueToday, dueYesterday));

        assertThat(service.findCopiesLentTo(dueToday.memberId()))
                .extracting(copy -> copy.loan().overdue())
                .containsExactly(false, true);
    }

    private static BookCopy lentCopy(LocalDate dueDate, int renewals) {
        return new BookCopy(DUNE.bookId(), UUID.randomUUID(), "LIB-000001", BookCopyStatus.LENT, Instant.now(),
                UUID.randomUUID(), MEMBER_ID, Instant.parse("2026-06-01T10:00:00Z"), dueDate, renewals);
    }

    private void givenExistingCopy(BookCopy copy) {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        givenStoredCopy(copy);
    }

    /** Only the copy, for operations that never look at the book itself. */
    private void givenStoredCopy(BookCopy copy) {
        when(copies.findById(BasicMapId.id("bookId", DUNE.bookId()).with("bookCopyId", copy.bookCopyId())))
                .thenReturn(Optional.of(copy));
    }

    private static BookCopy copy(String inventoryNumber, BookCopyStatus status) {
        return new BookCopy(DUNE.bookId(), UUID.randomUUID(), inventoryNumber, status, Instant.now(),
                null, null, null, null, null);
    }
}
