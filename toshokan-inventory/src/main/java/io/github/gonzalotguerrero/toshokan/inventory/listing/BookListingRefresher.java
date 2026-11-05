package io.github.gonzalotguerrero.toshokan.inventory.listing;

import io.github.gonzalotguerrero.toshokan.catalog.book.BookDetails;
import io.github.gonzalotguerrero.toshokan.inventory.bookcopy.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.bookcopy.BookCopyRepository;
import io.github.gonzalotguerrero.toshokan.inventory.bookcopy.BookCopyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.mapping.BasicMapId;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Updates a book's row in {@code book_listing} to match its copies. Call it whenever one of the book's copies
 * changes.
 *
 * <p>Instead of adding or subtracting one, it counts the copies again from {@code book_copies_by_book} every
 * time. That's an extra read of one partition, but if an update is ever missed, or two of them race, the
 * counts fix themselves on the next change.
 */
@Component
public class BookListingRefresher {

    private static final Logger log = LoggerFactory.getLogger(BookListingRefresher.class);

    private final BookListingRepository listing;
    private final BookCopyRepository copies;

    public BookListingRefresher(BookListingRepository listing, BookCopyRepository copies) {
        this.listing = listing;
        this.copies = copies;
    }

    public void refresh(BookDetails book) {
        List<BookCopy> bookCopies = copies.findByBookId(book.bookId());
        if (bookCopies.isEmpty()) {
            // No copies left, so the book comes off the listing
            listing.deleteById(BasicMapId.id("listingPartition", BookListingEntry.LISTING_PARTITION)
                    .with("sortTitle", BookListingEntry.sortKeyFor(book.title()))
                    .with("bookId", book.bookId()));
            log.debug("Removed book {} from the listing", book.bookId());
            return;
        }
        int available = (int) bookCopies.stream()
                .filter(copy -> copy.status() == BookCopyStatus.AVAILABLE)
                .count();
        listing.save(BookListingEntry.of(book.bookId(), book.isbn(), book.title(), book.authors(), book.publisher(),
                book.publicationYear(), book.language(), book.genres(), bookCopies.size(), available));
        log.debug("Listing for book {} now shows {} copies, {} available", book.bookId(), bookCopies.size(), available);
    }
}
