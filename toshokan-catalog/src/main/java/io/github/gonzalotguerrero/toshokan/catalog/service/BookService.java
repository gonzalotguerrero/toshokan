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
import io.github.gonzalotguerrero.toshokan.shared.paging.PageTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.cassandra.CassandraWriteTimeoutException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.gonzalotguerrero.toshokan.catalog.service.BookTextNormalizer.blankToNull;
import static io.github.gonzalotguerrero.toshokan.catalog.service.BookTextNormalizer.capitalize;
import static io.github.gonzalotguerrero.toshokan.catalog.service.BookTextNormalizer.normalizeGenres;
import static io.github.gonzalotguerrero.toshokan.catalog.service.BookTextNormalizer.normalizeIsbn;
import static io.github.gonzalotguerrero.toshokan.catalog.service.BookTextNormalizer.searchKey;

@Service
public class BookService {

    private static final Logger log = LoggerFactory.getLogger(BookService.class);

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    // The write type Cassandra reports when a logged batch timed out after it was already written to the batch log
    private static final String BATCH_ALREADY_LOGGED = "BATCH";

    private final BookRepository books;
    private final BookByIsbnRepository booksByIsbn;
    private final BookByTitleRepository booksByTitle;
    private final BookByAuthorRepository booksByAuthor;
    private final BookWriter writer;
    private final IsbnClaims isbnClaims;
    private final Clock clock;

    @Autowired
    public BookService(BookRepository books, BookByIsbnRepository booksByIsbn, BookByTitleRepository booksByTitle,
                       BookByAuthorRepository booksByAuthor, BookWriter writer, IsbnClaims isbnClaims) {
        this(books, booksByIsbn, booksByTitle, booksByAuthor, writer, isbnClaims, Clock.systemUTC());
    }

    /** Tests use this to fix the clock, so the "no future years" check gives the same result whenever they run. */
    public BookService(BookRepository books, BookByIsbnRepository booksByIsbn, BookByTitleRepository booksByTitle,
                       BookByAuthorRepository booksByAuthor, BookWriter writer, IsbnClaims isbnClaims,
                       Clock clock) {
        this.books = books;
        this.booksByIsbn = booksByIsbn;
        this.booksByTitle = booksByTitle;
        this.booksByAuthor = booksByAuthor;
        this.writer = writer;
        this.isbnClaims = isbnClaims;
        this.clock = clock;
    }

    /**
     * Normalises the request and saves the book, along with the rows that let it be found by title and author.
     * When there's an ISBN, we claim it first, so a duplicate gets rejected before anything else is written.
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
            writer.save(book);
        } catch (CassandraWriteTimeoutException e) {
            if (isbn != null && !BATCH_ALREADY_LOGGED.equals(e.getWriteType())) {
                isbnClaims.release(isbn, book.bookId());
            }
            // Otherwise Cassandra already has the batch and will finish applying it, so the book is coming and
            // the ISBN has to stay its own
            throw e;
        } catch (RuntimeException e) {
            if (isbn != null) {
                isbnClaims.release(isbn, book.bookId());
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

    /**
     * Finds books by exactly one of ISBN, author or title. Titles and authors have to match as a whole, but case,
     * spacing and character width are ignored (see {@link BookTextNormalizer#searchKey}). An author search comes
     * back sorted by title, a title search newest edition first.
     *
     * <p>An ISBN search has at most one result, so it never has a next page. An ISBN can be claimed by a book that
     * never got saved (when saving failed and the claim couldn't be released), so the book itself is read too, and
     * the claim alone doesn't count as a match.
     */
    public PageResponse<BookDetails> searchBooks(String isbn, String author, String title, int pageSize,
                                                 String pageToken) {
        String givenIsbn = blankToNull(isbn);
        String givenAuthor = blankToNull(author);
        String givenTitle = blankToNull(title);
        if (Stream.of(givenIsbn, givenAuthor, givenTitle).filter(value -> value != null).count() != 1) {
            throw new InvalidRequestException("Search by exactly one of isbn, author or title");
        }
        if (givenIsbn != null) {
            List<BookDetails> found = booksByIsbn.findById(normalizeIsbn(givenIsbn))
                    .map(BookByIsbn::bookId)
                    .flatMap(this::findBook)
                    .stream()
                    .toList();
            return new PageResponse<>(found, null);
        }
        if (givenAuthor != null) {
            String key = searchKey(givenAuthor);
            Slice<BookByAuthor> slice = page(pageSize, pageToken, request -> booksByAuthor.findByAuthorKey(key, request));
            return new PageResponse<>(slice.map(row -> BookDetails.from(row.toBook())).getContent(), nextToken(slice));
        }
        String key = searchKey(givenTitle);
        Slice<BookByTitle> slice = page(pageSize, pageToken, request -> booksByTitle.findByTitleKey(key, request));
        return new PageResponse<>(slice.map(row -> BookDetails.from(row.toBook())).getContent(), nextToken(slice));
    }

    private static <T> Slice<T> page(int pageSize, String pageToken, Function<CassandraPageRequest, Slice<T>> query) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        ByteBuffer pagingState = PageTokens.decode(pageToken);
        try {
            return query.apply(CassandraPageRequest.of(PageRequest.of(0, pageSize), pagingState));
        } catch (DataAccessException e) {
            if (pagingState != null) {
                // A tampered token, or one from a different query, makes Cassandra fail
                throw new InvalidRequestException("Invalid page token", e);
            }
            throw e;
        }
    }

    private static String nextToken(Slice<?> slice) {
        return slice.hasNext()
                ? PageTokens.encode(((CassandraPageRequest) slice.nextPageable()).getPagingState())
                : null;
    }
}
