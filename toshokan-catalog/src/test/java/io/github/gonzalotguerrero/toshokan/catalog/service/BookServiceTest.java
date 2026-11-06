package io.github.gonzalotguerrero.toshokan.catalog.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.dto.CreateBookRequest;
import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import io.github.gonzalotguerrero.toshokan.catalog.repository.BookRepository;
import io.github.gonzalotguerrero.toshokan.catalog.repository.IsbnClaims;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
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
    private IsbnClaims isbnClaims;

    private BookService service;

    @BeforeEach
    void setUp() {
        service = new BookService(books, isbnClaims, Clock.fixed(NOW, ZoneOffset.UTC));
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
        when(isbnClaims.claim(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.createBook(request("9780441013593")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A book with ISBN 9780441013593 already exists");
        verify(books, never()).insert(any(Book.class));
    }

    @Test
    void givesTheIsbnBackIfSavingTheBookFails() {
        when(isbnClaims.claim(anyString(), any())).thenReturn(true);
        when(books.insert(any(Book.class))).thenThrow(new IllegalStateException("Cassandra is down"));

        assertThatThrownBy(() -> service.createBook(request("9780441013593")))
                .isInstanceOf(IllegalStateException.class);
        verify(isbnClaims).release(eq("9780441013593"), any());
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

    private Book captureInsertedBook() {
        ArgumentCaptor<Book> captor = ArgumentCaptor.forClass(Book.class);
        verify(books).insert(captor.capture());
        return captor.getValue();
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
