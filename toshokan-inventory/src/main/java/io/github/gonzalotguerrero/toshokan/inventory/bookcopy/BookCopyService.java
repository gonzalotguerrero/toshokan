package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import io.github.gonzalotguerrero.toshokan.catalog.book.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.book.BookService;
import io.github.gonzalotguerrero.toshokan.inventory.holdings.BookHoldingsRefresher;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class BookCopyService {

    private static final Logger log = LoggerFactory.getLogger(BookCopyService.class);

    private final BookService books;
    private final BookCopyRepository copies;
    private final InventoryNumberClaims inventoryNumbers;
    private final BookHoldingsRefresher holdingsRefresher;

    public BookCopyService(BookService books, BookCopyRepository copies, InventoryNumberClaims inventoryNumbers,
                           BookHoldingsRefresher holdingsRefresher) {
        this.books = books;
        this.copies = copies;
        this.inventoryNumbers = inventoryNumbers;
        this.holdingsRefresher = holdingsRefresher;
    }

    /**
     * Adds a new copy of a book, on the shelf and ready to be lent. If it's the book's first copy, the book
     * appears in the holdings; otherwise its counts go up.
     */
    public BookCopyDetails addBookCopy(UUID bookId, AddBookCopyRequest request) {
        BookDetails book = books.findBook(bookId)
                .orElseThrow(() -> new NotFoundException("No book with id " + bookId));
        String inventoryNumber = request.inventoryNumber().strip();
        BookCopy copy = new BookCopy(bookId, UUID.randomUUID(), inventoryNumber, BookCopyStatus.AVAILABLE,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        if (!inventoryNumbers.claim(inventoryNumber, bookId, copy.bookCopyId())) {
            throw new ConflictException("Inventory number " + inventoryNumber + " is already in use");
        }
        try {
            copies.insert(copy);
        } catch (RuntimeException e) {
            inventoryNumbers.release(inventoryNumber);
            throw e;
        }
        log.info("Added copy {} ({}) of book {}", copy.bookCopyId(), inventoryNumber, bookId);

        try {
            holdingsRefresher.refresh(book);
        } catch (RuntimeException e) {
            // The copy is already saved, so we don't fail the request. A retry would only get "number already
            // in use". The holdings get fixed the next time any copy of this book changes.
            log.warn("Book copy {} was added but the holdings for book {} couldn't be updated",
                    copy.bookCopyId(), bookId, e);
        }
        return BookCopyDetails.from(copy);
    }
}
