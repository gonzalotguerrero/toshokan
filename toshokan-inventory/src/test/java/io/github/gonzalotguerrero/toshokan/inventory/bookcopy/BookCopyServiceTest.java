package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import io.github.gonzalotguerrero.toshokan.catalog.book.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.book.BookService;
import io.github.gonzalotguerrero.toshokan.inventory.listing.BookListingRefresher;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private BookListingRefresher listingRefresher;

    @InjectMocks
    private BookCopyService service;

    @Test
    void addsAnAvailableCopyAndRefreshesTheListing() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(eq("LIB-000123"), eq(DUNE.bookId()), any())).thenReturn(true);

        BookCopyDetails added = service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("  LIB-000123 "));

        ArgumentCaptor<BookCopy> saved = ArgumentCaptor.forClass(BookCopy.class);
        verify(copies).insert(saved.capture());
        assertThat(saved.getValue().bookId()).isEqualTo(DUNE.bookId());
        assertThat(saved.getValue().inventoryNumber()).isEqualTo("LIB-000123");
        assertThat(saved.getValue().status()).isEqualTo(BookCopyStatus.AVAILABLE);
        assertThat(added).isEqualTo(BookCopyDetails.from(saved.getValue()));
        verify(inventoryNumbers).claim("LIB-000123", DUNE.bookId(), added.bookCopyId());
        verify(listingRefresher).refresh(DUNE);
    }

    @Test
    void failsWithNotFoundForAnUnknownBook() {
        UUID bookId = UUID.randomUUID();
        when(books.findBook(bookId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addBookCopy(bookId, new AddBookCopyRequest("LIB-000123")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No book with id " + bookId);
        verifyNoInteractions(copies, inventoryNumbers, listingRefresher);
    }

    @Test
    void rejectsAnInventoryNumberAlreadyInUse() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("LIB-000123")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Inventory number LIB-000123 is already in use");
        verify(copies, never()).insert(any(BookCopy.class));
        verifyNoInteractions(listingRefresher);
    }

    @Test
    void givesTheInventoryNumberBackIfSavingTheCopyFails() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(anyString(), any(), any())).thenReturn(true);
        when(copies.insert(any(BookCopy.class))).thenThrow(new IllegalStateException("Cassandra is down"));

        assertThatThrownBy(() -> service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("LIB-000123")))
                .isInstanceOf(IllegalStateException.class);
        verify(inventoryNumbers).release("LIB-000123");
        verifyNoInteractions(listingRefresher);
    }

    @Test
    void stillReturnsTheCopyWhenTheListingCannotBeRefreshed() {
        when(books.findBook(DUNE.bookId())).thenReturn(Optional.of(DUNE));
        when(inventoryNumbers.claim(anyString(), any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("Cassandra hiccup")).when(listingRefresher).refresh(DUNE);

        BookCopyDetails added = service.addBookCopy(DUNE.bookId(), new AddBookCopyRequest("LIB-000123"));

        assertThat(added.inventoryNumber()).isEqualTo("LIB-000123");
        verify(inventoryNumbers, never()).release(anyString());
    }
}
