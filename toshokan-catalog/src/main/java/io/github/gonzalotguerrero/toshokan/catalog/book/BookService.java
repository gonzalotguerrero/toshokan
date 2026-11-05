package io.github.gonzalotguerrero.toshokan.catalog.book;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static io.github.gonzalotguerrero.toshokan.catalog.book.BookTextNormalizer.blankToNull;
import static io.github.gonzalotguerrero.toshokan.catalog.book.BookTextNormalizer.capitalize;
import static io.github.gonzalotguerrero.toshokan.catalog.book.BookTextNormalizer.normalizeGenres;
import static io.github.gonzalotguerrero.toshokan.catalog.book.BookTextNormalizer.normalizeIsbn;

@Service
public class BookService {

    private static final Logger log = LoggerFactory.getLogger(BookService.class);

    private final BookRepository books;
    private final IsbnClaims isbnClaims;
    private final Clock clock;

    @Autowired
    public BookService(BookRepository books, IsbnClaims isbnClaims) {
        this(books, isbnClaims, Clock.systemUTC());
    }

    /** Tests use this to fix the clock, so the "no future years" check gives the same result whenever they run. */
    BookService(BookRepository books, IsbnClaims isbnClaims, Clock clock) {
        this.books = books;
        this.isbnClaims = isbnClaims;
        this.clock = clock;
    }

    /**
     * Normalises the request and saves the book. When there's an ISBN, we claim it first, so a duplicate
     * gets rejected before anything is written to {@code books}.
     */
    public BookDetails createBook(CreateBookRequest request) {
        int publicationYear = request.publicationYear();
        if (publicationYear > Year.now(clock).getValue()) {
            throw new InvalidRequestException("publicationYear can't be in the future");
        }
        String isbn = normalizeIsbn(request.isbn());
        Book book = new Book(
                UUID.randomUUID(),
                isbn,
                request.title().strip(),
                request.authors().stream().map(String::strip).toList(),
                blankToNull(request.publisher()),
                publicationYear,
                capitalize(request.language()),
                normalizeGenres(request.genres()),
                Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));

        if (isbn != null && !isbnClaims.claim(isbn, book.bookId())) {
            throw new ConflictException("A book with ISBN " + isbn + " already exists");
        }
        try {
            books.insert(book);
        } catch (RuntimeException e) {
            if (isbn != null) {
                isbnClaims.release(isbn);
            }
            throw e;
        }
        log.info("Created book {} \"{}\"", book.bookId(), book.title());
        return BookDetails.from(book);
    }

    public BookDetails getBook(UUID bookId) {
        return findBook(bookId).orElseThrow(() -> new NotFoundException("No book with id " + bookId));
    }

    /** Used by other modules to check that a book exists, or to read its details. */
    public Optional<BookDetails> findBook(UUID bookId) {
        return books.findById(bookId).map(BookDetails::from);
    }
}
