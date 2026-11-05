package io.github.gonzalotguerrero.toshokan.inventory.listing;

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

@Service
public class BookListingService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(BookListingService.class);

    private final BookListingRepository repository;

    public BookListingService(BookListingRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists the titles the library owns, one page at a time.
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

        Slice<BookListingEntry> slice;
        try {
            slice = onlyAvailable
                    ? repository.findByListingPartitionAndAvailableCopiesGreaterThan(
                            BookListingEntry.LISTING_PARTITION, 0, pageRequest)
                    : repository.findByListingPartition(BookListingEntry.LISTING_PARTITION, pageRequest);
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
        log.debug("Returning {} books (more pages: {})", slice.getNumberOfElements(), nextPageToken != null);
        return new PageResponse<>(slice.map(BookSummary::from).getContent(), nextPageToken);
    }
}
