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
import io.github.gonzalotguerrero.toshokan.shared.cassandra.StaleClaims;
import io.github.gonzalotguerrero.toshokan.shared.cassandra.WriteOutcomes;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    private final BookRepository books;
    private final BookByIsbnRepository booksByIsbn;
    private final BookByTitleRepository booksByTitle;
    private final BookByAuthorRepository booksByAuthor;
    private final BookWriter writer;
    private final IsbnClaims isbnClaims;
    private final BookDeletionGuard deletionGuard;
    private final Clock clock;

    @Autowired
    public BookService(BookRepository books, BookByIsbnRepository booksByIsbn, BookByTitleRepository booksByTitle,
                       BookByAuthorRepository booksByAuthor, BookWriter writer, IsbnClaims isbnClaims,
                       BookDeletionGuard deletionGuard) {
        this(books, booksByIsbn, booksByTitle, booksByAuthor, writer, isbnClaims, deletionGuard, Clock.systemUTC());
    }

    /** Tests use this to fix the clock, so the "no future years" check gives the same result whenever they run. */
    public BookService(BookRepository books, BookByIsbnRepository booksByIsbn, BookByTitleRepository booksByTitle,
                       BookByAuthorRepository booksByAuthor, BookWriter writer, IsbnClaims isbnClaims,
                       BookDeletionGuard deletionGuard, Clock clock) {
        this.books = books;
        this.booksByIsbn = booksByIsbn;
        this.booksByTitle = booksByTitle;
        this.booksByAuthor = booksByAuthor;
        this.writer = writer;
        this.isbnClaims = isbnClaims;
        this.deletionGuard = deletionGuard;
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

        if (isbn != null && !claimIsbn(isbn, book.bookId())) {
            throw new ConflictException("A book with ISBN " + isbn + " already exists");
        }
        try {
            writer.save(book);
        } catch (RuntimeException e) {
            // If the book might still get saved (a timeout, say), the ISBN has to stay its own. Should the book
            // never arrive, the claim goes stale and the next book with this ISBN takes it over
            if (isbn != null && WriteOutcomes.definitelyNotApplied(e)) {
                isbnClaims.release(isbn, book.bookId());
            }
            throw e;
        }
        log.info("Created book {} \"{}\"", book.bookId(), book.title());
        return BookDetails.from(book);
    }

    /**
     * Deletes a book, as long as it has no copies or only withdrawn ones (inventory decides that, see
     * {@link BookDeletionGuard}). Withdrawn copies and loan history stay as a record. The ISBN is freed afterwards,
     * so the same edition can be catalogued again later.
     */
    public void deleteBook(UUID bookId) {
        Book book = books.findById(bookId).orElseThrow(() -> new NotFoundException("No book with id " + bookId));
        deletionGuard.runGuarded(BookDetails.from(book), () -> writer.delete(book));
        log.info("Deleted book {} \"{}\"", book.bookId(), book.title());
        if (book.isbn() != null) {
            // Only after the book is gone: freeing it first would let another book take the ISBN while this one
            // still existed
            try {
                isbnClaims.release(book.isbn(), bookId);
            } catch (RuntimeException e) {
                // The book is deleted either way. The claim now points at a book that doesn't exist, and the next
                // book with this ISBN takes it over (see claimIsbn)
                log.warn("Couldn't free ISBN {} of deleted book {}", book.isbn(), bookId, e);
            }
        }
    }

    public BookDetails getBook(UUID bookId) {
        return findBook(bookId).orElseThrow(() -> new NotFoundException("No book with id " + bookId));
    }

    /** Used by other modules to check that a book exists, or to read its details. */
    public Optional<BookDetails> findBook(UUID bookId) {
        return books.findById(bookId).map(BookDetails::from);
    }

    /** Which of these books still exist, read in one go. Used by inventory to skip rows about deleted books. */
    public Set<UUID> findExistingBookIds(Collection<UUID> bookIds) {
        if (bookIds.isEmpty()) {
            return Set.of();
        }
        return books.findAllById(bookIds).stream().map(Book::bookId).collect(Collectors.toSet());
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

    /**
     * Claims an ISBN for a new book. If another book holds it, the claim is only taken over when it's stale (see
     * {@link StaleClaims}). That happens when a create failed, or a delete couldn't free the ISBN.
     */
    private boolean claimIsbn(String isbn, UUID bookId) {
        if (isbnClaims.claim(isbn, bookId)) {
            return true;
        }
        Optional<IsbnClaims.Claim> current = isbnClaims.currentClaim(isbn);
        if (current.isEmpty()) {
            // Freed between our claim and this read, so it's worth one more try
            return isbnClaims.claim(isbn, bookId);
        }
        IsbnClaims.Claim claim = current.get();
        boolean stale = StaleClaims.isStale(claim.claimedAt(), books.existsById(claim.bookId()), clock);
        if (stale && isbnClaims.takeOver(isbn, claim.bookId(), bookId)) {
            log.info("Took over ISBN {} from book {}, which doesn't exist", isbn, claim.bookId());
            return true;
        }
        return false;
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
