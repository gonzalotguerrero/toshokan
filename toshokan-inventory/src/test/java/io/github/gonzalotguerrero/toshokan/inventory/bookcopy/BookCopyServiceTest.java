package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import io.github.gonzalotguerrero.toshokan.catalog.book.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.book.BookService;
import io.github.gonzalotguerrero.toshokan.inventory.holdings.BookHoldingsRefresher;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.mapping.BasicMapId;

import java.time.Instant;
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

    private static final BookDetails DUNE = new BookDetails(UUID.randomUUID(), null, "Dune",
            List.of("Frank Herbert"), null, 1965, "English", Set.of("Science fiction"), Instant.now());

    @Mock
    private BookService books;

    @Mock
    private BookCopyRepository copies;

    @Mock
    private InventoryNumberClaims inventoryNumbers;

    @Mock
    private NewBookCopyInserter newCopies;

    @Mock
    private BookCopyStatusChanges statusChanges;

    @Mock
    private BookHoldingsRefresher holdingsRefresher;

    @InjectMocks
    private BookCopyService service;

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
        assertThat(added).isEqualTo(BookCopyDetails.from(saved.getValue()));
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
        verify(inventoryNumbers).release("LIB-000123");
        verifyNoInteractions(holdingsRefresher);
    }

    @Test
    void stillReturnsTheCopyWhenTheHoldingsCannotBeRefreshed() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(anyString(), any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("Cassandra hiccup")).when(holdingsRefresher).refresh(DUNE);

        BookCopyDetails added = service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("LIB-000123"));

        assertThat(added.inventoryNumber()).isEqualTo("LIB-000123");
        verify(inventoryNumbers, never()).release(anyString());
    }

    @Test
    void listsEveryCopySortedByInventoryNumber() {
        BookCopy second = copy("LIB-000002", BookCopyStatus.WITHDRAWN);
        BookCopy first = copy("LIB-000001", BookCopyStatus.AVAILABLE);
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of(second, first));

        assertThat(service.listBookCopies(DUNE.bookId()))
                .containsExactly(BookCopyDetails.from(first), BookCopyDetails.from(second));
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

        assertThat(unchanged).isEqualTo(BookCopyDetails.from(copy));
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
        verify(inventoryNumbers, never()).release(anyString());
    }

    @Test
    void refusesToWithdrawACopyThatIsLentOut() {
        BookCopy copy = copy("LIB-000001", BookCopyStatus.LENT);
        givenExistingCopy(copy);

        assertThatThrownBy(() -> service.withdraw(DUNE.bookId(), copy.bookCopyId()))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(statusChanges, holdingsRefresher);
    }

    private void givenExistingCopy(BookCopy copy) {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(copies.findById(BasicMapId.id("bookId", DUNE.bookId()).with("bookCopyId", copy.bookCopyId())))
                .thenReturn(Optional.of(copy));
    }

    private static BookCopy copy(String inventoryNumber, BookCopyStatus status) {
        return new BookCopy(DUNE.bookId(), UUID.randomUUID(), inventoryNumber, status, Instant.now());
    }
}
