package io.github.gonzalotguerrero.toshokan.catalog.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.dto.CreateBookRequest;
import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByAuthor;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByIsbn;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByTitle;
import io.github.gonzalotguerrero.toshokan.catalog.repository.BookByAuthorRepository;
import io.github.gonzalotguerrero.toshokan.catalog.repository.BookByIsbnRepository;
import io.github.gonzalotguerrero.toshokan.catalog.repository.BookByTitleRepository;
import io.github.gonzalotguerrero.toshokan.catalog.repository.BookRepository;
import io.github.gonzalotguerrero.toshokan.catalog.repository.BookWriter;
import io.github.gonzalotguerrero.toshokan.catalog.repository.IsbnClaims;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.cassandra.CassandraWriteTimeoutException;
import org.springframework.data.domain.SliceImpl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookServiceTest {

    // Has digits below the millisecond on purpose, to check that they get dropped
    private static final Instant NOW = Instant.parse("2026-06-15T10:00:00.123456789Z");

    @Mock
    private BookRepository books;

    @Mock
    private BookByIsbnRepository booksByIsbn;

    @Mock
    private BookByTitleRepository booksByTitle;

    @Mock
    private BookByAuthorRepository booksByAuthor;

    @Mock
    private BookWriter writer;

    @Mock
    private IsbnClaims isbnClaims;

    @Mock
    private BookDeletionGuard deletionGuard;

    private BookService service;

    @BeforeEach
    void setUp() {
        service = new BookService(books, booksByIsbn, booksByTitle, booksByAuthor, writer, isbnClaims,
                deletionGuard, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void normalizesAndSavesTheBook() {
        when(isbnClaims.claim(eq("9780441013593"), any())).thenReturn(true);

        BookDetails created = service.createBook(new CreateBookRequest("978-0-441-01359-3", "  Dune ",
                List.of(" Frank Herbert "), " Ace ", 1965, "eNGLISH", List.of("science FICTION", "Science fiction")));

        Book saved = captureInsertedBook();
        assertThat(saved.isbn()).isEqualTo("9780441013593");
        assertThat(saved.title()).isEqualTo("Dune");
        assertThat(saved.authors()).containsExactly("Frank Herbert");
        assertThat(saved.publisher()).isEqualTo("Ace");
        assertThat(saved.publicationYear()).isEqualTo(1965);
        assertThat(saved.language()).isEqualTo("English");
        assertThat(saved.genres()).containsExactly("Science fiction");
        assertThat(saved.addedAt()).isEqualTo(Instant.parse("2026-06-15T10:00:00.123Z"));
        assertThat(created).isEqualTo(BookDetails.from(saved));
    }

    @Test
    void claimsTheIsbnForTheNewBook() {
        when(isbnClaims.claim(anyString(), any())).thenReturn(true);

        BookDetails created = service.createBook(request("9780441013593"));

        verify(isbnClaims).claim("9780441013593", created.bookId());
    }

    @Test
    void savesABookWithoutIsbnOrPublisher() {
        service.createBook(new CreateBookRequest(null, "Dune", List.of("Frank Herbert"), "  ", 1965,
                "English", List.of("Fiction")));

        Book saved = captureInsertedBook();
        assertThat(saved.isbn()).isNull();
        assertThat(saved.publisher()).isNull();
        verifyNoInteractions(isbnClaims);
    }

    @Test
    void rejectsAnIsbnAnotherBookHas() {
        Book other = new Book(UUID.randomUUID(), "9780441013593", "Dune", List.of("Frank Herbert"), null, 1965,
                "English", Set.of("Fiction"), NOW);
        when(isbnClaims.claim(anyString(), any())).thenReturn(false);
        when(isbnClaims.currentClaim("9780441013593"))
                .thenReturn(Optional.of(new IsbnClaims.Claim(other.bookId(), NOW.minus(Duration.ofDays(30)))));
        when(books.findById(other.bookId())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.createBook(request("9780441013593")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A book with ISBN 9780441013593 already exists");
        verify(writer, never()).save(any());
        verify(isbnClaims, never()).takeOver(any(), any(), any());
    }

    @Test
    void givesTheIsbnBackIfSavingTheBookFails() {
        when(isbnClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("Cassandra is down")).when(writer).save(any());

        assertThatThrownBy(() -> service.createBook(request("9780441013593")))
                .isInstanceOf(IllegalStateException.class);
        verify(isbnClaims).release(eq("9780441013593"), any());
    }

    @Test
    void givesTheIsbnBackIfTheBatchTimedOutBeforeItWasLogged() {
        when(isbnClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new CassandraWriteTimeoutException("BATCH_LOG", "timed out", null)).when(writer).save(any());

        assertThatThrownBy(() -> service.createBook(request("9780441013593")))
                .isInstanceOf(CassandraWriteTimeoutException.class);
        verify(isbnClaims).release(eq("9780441013593"), any());
    }

    @Test
    void keepsTheIsbnIfTheBatchWasAlreadyLogged() {
        when(isbnClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new CassandraWriteTimeoutException("BATCH", "timed out", null)).when(writer).save(any());

        assertThatThrownBy(() -> service.createBook(request("9780441013593")))
                .isInstanceOf(CassandraWriteTimeoutException.class);
        // The book is still going to be written, so the ISBN has to stay its own
        verify(isbnClaims, never()).release(any(), any());
    }

    @Test
    void acceptsThisYear() {
        service.createBook(requestForYear(2026));

        assertThat(captureInsertedBook().publicationYear()).isEqualTo(2026);
    }

    @Test
    void rejectsAFuturePublicationYear() {
        assertThatThrownBy(() -> service.createBook(requestForYear(2027)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("publicationYear can't be in the future");
        verifyNoInteractions(books, isbnClaims);
    }

    @Test
    void getsAnExistingBook() {
        Book dune = new Book(UUID.randomUUID(), null, "Dune", List.of("Frank Herbert"), null, 1965, "English",
                Set.of("Fiction"), NOW);
        when(books.findById(dune.bookId())).thenReturn(Optional.of(dune));

        assertThat(service.getBook(dune.bookId())).isEqualTo(BookDetails.from(dune));
    }

    @Test
    void failsWithNotFoundForAnUnknownBook() {
        UUID bookId = UUID.randomUUID();
        when(books.findById(bookId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBook(bookId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No book with id " + bookId);
    }

    @Test
    void takesOverAnOldClaimWhoseBookDoesNotExist() {
        UUID gone = UUID.randomUUID();
        when(isbnClaims.claim(eq("9780441013593"), any())).thenReturn(false);
        when(isbnClaims.currentClaim("9780441013593"))
                .thenReturn(Optional.of(new IsbnClaims.Claim(gone, NOW.minus(Duration.ofHours(1)))));
        when(books.findById(gone)).thenReturn(Optional.empty());
        when(isbnClaims.takeOver(eq("9780441013593"), eq(gone), any())).thenReturn(true);

        BookDetails created = service.createBook(request("9780441013593"));

        verify(isbnClaims).takeOver("9780441013593", gone, created.bookId());
        assertThat(captureInsertedBook().isbn()).isEqualTo("9780441013593");
    }

    @Test
    void leavesAYoungClaimAloneEvenIfItsBookIsNotThereYet() {
        // Another create may have claimed it a moment ago and be about to save its book
        when(isbnClaims.claim(eq("9780441013593"), any())).thenReturn(false);
        when(isbnClaims.currentClaim("9780441013593"))
                .thenReturn(Optional.of(new IsbnClaims.Claim(UUID.randomUUID(), NOW.minusSeconds(5))));

        assertThatThrownBy(() -> service.createBook(request("9780441013593")))
                .isInstanceOf(ConflictException.class);
        verify(isbnClaims, never()).takeOver(any(), any(), any());
    }

    @Test
    void triesTheClaimAgainIfItWasFreedInTheMeantime() {
        when(isbnClaims.claim(eq("9780441013593"), any())).thenReturn(false, true);
        when(isbnClaims.currentClaim("9780441013593")).thenReturn(Optional.empty());

        service.createBook(request("9780441013593"));

        captureInsertedBook();
    }

    @Test
    void deletesABookThroughTheGuardAndThenFreesItsIsbn() {
        Book dune = new Book(UUID.randomUUID(), "9780441013593", "Dune", List.of("Frank Herbert"), null, 1965,
                "English", Set.of("Fiction"), NOW);
        when(books.findById(dune.bookId())).thenReturn(Optional.of(dune));
        givenTheGuardAllowsDeletion();

        service.deleteBook(dune.bookId());

        InOrder order = inOrder(deletionGuard, writer, isbnClaims);
        order.verify(deletionGuard).runGuarded(eq(BookDetails.from(dune)), any());
        order.verify(writer).delete(dune);
        // Only once the book is gone, or another book could take the ISBN while this one still exists
        order.verify(isbnClaims).release("9780441013593", dune.bookId());
    }

    @Test
    void theDeletionStandsEvenIfTheIsbnCannotBeFreed() {
        Book dune = new Book(UUID.randomUUID(), "9780441013593", "Dune", List.of("Frank Herbert"), null, 1965,
                "English", Set.of("Fiction"), NOW);
        when(books.findById(dune.bookId())).thenReturn(Optional.of(dune));
        givenTheGuardAllowsDeletion();
        doThrow(new IllegalStateException("Cassandra is down")).when(isbnClaims).release(any(), any());

        service.deleteBook(dune.bookId());

        verify(writer).delete(dune);
    }

    @Test
    void nothingIsDeletedWhenTheGuardRefuses() {
        Book dune = new Book(UUID.randomUUID(), "9780441013593", "Dune", List.of("Frank Herbert"), null, 1965,
                "English", Set.of("Fiction"), NOW);
        when(books.findById(dune.bookId())).thenReturn(Optional.of(dune));
        doThrow(new ConflictException("Book still has copies")).when(deletionGuard).runGuarded(any(), any());

        assertThatThrownBy(() -> service.deleteBook(dune.bookId())).isInstanceOf(ConflictException.class);
        verifyNoInteractions(writer, isbnClaims);
    }

    @Test
    void deletingAnUnknownBookFailsWithNotFound() {
        UUID bookId = UUID.randomUUID();
        when(books.findById(bookId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteBook(bookId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No book with id " + bookId);
        verifyNoInteractions(deletionGuard);
    }

    /** Makes the guard let the deletion through, like inventory would for a book without copies. */
    private void givenTheGuardAllowsDeletion() {
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(deletionGuard).runGuarded(any(), any());
    }

    @Test
    void findsABookByIsbnHoweverItIsWritten() {
        Book dune = dune(1965);
        when(booksByIsbn.findById("9780441013593")).thenReturn(Optional.of(new BookByIsbn("9780441013593",
                dune.bookId())));
        when(books.findById(dune.bookId())).thenReturn(Optional.of(dune));

        PageResponse<BookDetails> page = service.searchBooks(" 978-0-441-01359-3 ", null, null, 20, null);

        assertThat(page.items()).containsExactly(BookDetails.from(dune));
        assertThat(page.nextPageToken()).isNull();
    }

    @Test
    void anIsbnClaimWithoutItsBookIsNotAMatch() {
        when(booksByIsbn.findById("9780441013593")).thenReturn(Optional.of(new BookByIsbn("9780441013593",
                UUID.randomUUID())));
        when(books.findById(any())).thenReturn(Optional.empty());

        assertThat(service.searchBooks("9780441013593", null, null, 20, null).items()).isEmpty();
    }

    @Test
    void searchesByAuthorUsingTheSearchKey() {
        Book dune = dune(1965);
        when(booksByAuthor.findByAuthorKey(eq("frank herbert"), any()))
                .thenReturn(new SliceImpl<>(BookByAuthor.of(dune)));

        PageResponse<BookDetails> page = service.searchBooks(null, "  FRANK   herbert ", null, 20, null);

        assertThat(page.items()).containsExactly(BookDetails.from(dune));
    }

    @Test
    void searchesByTitleUsingTheSearchKey() {
        Book first = dune(1965);
        Book reissue = dune(2005);
        when(booksByTitle.findByTitleKey(eq("dune"), any()))
                .thenReturn(new SliceImpl<>(List.of(BookByTitle.of(reissue), BookByTitle.of(first))));

        PageResponse<BookDetails> page = service.searchBooks(null, null, "\uFF24\uFF35\uFF2E\uFF25", 20, null);

        assertThat(page.items()).extracting(BookDetails::publicationYear).containsExactly(2005, 1965);
    }

    @Test
    void needsExactlyOneThingToSearchBy() {
        assertThatThrownBy(() -> service.searchBooks(null, " ", null, 20, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Search by exactly one of isbn, author or title");
        assertThatThrownBy(() -> service.searchBooks(null, "Frank Herbert", "Dune", 20, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Search by exactly one of isbn, author or title");
        verifyNoInteractions(booksByIsbn, booksByAuthor, booksByTitle);
    }

    @Test
    void refusesAPageSizeOutOfRange() {
        assertThatThrownBy(() -> service.searchBooks(null, "Frank Herbert", null, 0, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("pageSize must be between 1 and 100");
    }

    @Test
    void answersABrokenPageTokenWithABadRequest() {
        when(booksByTitle.findByTitleKey(eq("dune"), any()))
                .thenThrow(new DataAccessResourceFailureException("Invalid paging state"));

        assertThatThrownBy(() -> service.searchBooks(null, null, "Dune", 20, "AAAA"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Invalid page token");
    }

    private Book captureInsertedBook() {
        ArgumentCaptor<Book> captor = ArgumentCaptor.forClass(Book.class);
        verify(writer).save(captor.capture());
        return captor.getValue();
    }

    private static Book dune(int year) {
        return new Book(UUID.randomUUID(), null, "Dune", List.of("Frank Herbert"), null, year, "English",
                Set.of("Fiction"), NOW);
    }

    private static CreateBookRequest request(String isbn) {
        return new CreateBookRequest(isbn, "Dune", List.of("Frank Herbert"), null, 1965, "English",
                List.of("Fiction"));
    }

    private static CreateBookRequest requestForYear(int year) {
        return new CreateBookRequest(null, "Dune", List.of("Frank Herbert"), null, year, "English",
                List.of("Fiction"));
    }
}
