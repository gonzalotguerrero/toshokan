package io.github.gonzalotguerrero.toshokan.inventory.service;

import io.github.gonzalotguerrero.toshokan.catalog.service.BookService;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookSummary;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookHoldings;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookHoldingsRepository;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class BookHoldingsService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(BookHoldingsService.class);

    private final BookHoldingsRepository repository;
    private final BookService books;

    public BookHoldingsService(BookHoldingsRepository repository, BookService books) {
        this.repository = repository;
        this.books = books;
    }

    /**
     * Lists the titles the library owns, one page at a time.
     *
     * <p>Rows whose book no longer exists are skipped. A book's holdings are only fixed by the next change to one
     * of its copies, and after a book is deleted there never is one, so a refresh that failed during the delete
     * would otherwise leave the title listed for good. Because of that, a page can have fewer items than asked
     * for even when more pages follow.
     *
     * @param onlyAvailable if {@code true}, leave out titles whose copies are all lent out
     * @param pageSize      between 1 and {@value #MAX_PAGE_SIZE}
     * @param pageToken     {@code null} for the first page, then the {@code nextPageToken} from the page before
     */
    public PageResponse<BookSummary> listBooks(boolean onlyAvailable, int pageSize, String pageToken) {
        log.debug("Listing books (onlyAvailable={}, pageSize={}, firstPage={})",
                onlyAvailable, pageSize, pageToken == null);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        ByteBuffer pagingState = PageTokens.decode(pageToken);
        CassandraPageRequest pageRequest = CassandraPageRequest.of(PageRequest.of(0, pageSize), pagingState);

        Slice<BookHoldings> slice;
        try {
            slice = onlyAvailable
                    ? repository.findByHoldingsPartitionAndAvailableCopiesGreaterThan(
                            BookHoldings.HOLDINGS_PARTITION, 0, pageRequest)
                    : repository.findByHoldingsPartition(BookHoldings.HOLDINGS_PARTITION, pageRequest);
        } catch (DataAccessException e) {
            if (pagingState != null) {
                // A tampered token, or one from a different query, makes Cassandra fail
                throw new InvalidRequestException("Invalid page token", e);
            }
            throw e;
        }

        String nextPageToken = slice.hasNext()
                ? PageTokens.encode(((CassandraPageRequest) slice.nextPageable()).getPagingState())
                : null;
        List<BookHoldings> rows = slice.getContent();
        Set<UUID> existing = books.findExistingBookIds(rows.stream().map(BookHoldings::bookId).toList());
        List<BookSummary> page = rows.stream()
                .filter(row -> existing.contains(row.bookId()))
                .map(BookSummary::from)
                .toList();
        log.debug("Returning {} books (more pages: {})", page.size(), nextPageToken != null);
        return new PageResponse<>(page, nextPageToken);
    }
}
