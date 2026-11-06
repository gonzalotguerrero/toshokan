package io.github.gonzalotguerrero.toshokan.inventory.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.service.BookService;
import io.github.gonzalotguerrero.toshokan.inventory.dto.AddBookCopyRequest;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyRepository;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyStatusChanges;
import io.github.gonzalotguerrero.toshokan.inventory.repository.InventoryNumberClaims;
import io.github.gonzalotguerrero.toshokan.inventory.repository.NewBookCopyInserter;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.mapping.BasicMapId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class BookCopyService {

    private static final Logger log = LoggerFactory.getLogger(BookCopyService.class);

    private final BookService books;
    private final BookCopyRepository copies;
    private final InventoryNumberClaims inventoryNumbers;
    private final NewBookCopyInserter newCopies;
    private final BookCopyStatusChanges statusChanges;
    private final BookHoldingsRefresher holdingsRefresher;

    public BookCopyService(BookService books, BookCopyRepository copies, InventoryNumberClaims inventoryNumbers,
                           NewBookCopyInserter newCopies, BookCopyStatusChanges statusChanges,
                           BookHoldingsRefresher holdingsRefresher) {
        this.books = books;
        this.copies = copies;
        this.inventoryNumbers = inventoryNumbers;
        this.newCopies = newCopies;
        this.statusChanges = statusChanges;
        this.holdingsRefresher = holdingsRefresher;
    }

    /**
     * Adds a new copy of a book, on the shelf and ready to be lent. If it's the book's first copy, the book
     * appears in the holdings; otherwise its counts go up.
     */
    public BookCopyDetails addBookCopy(UUID bookId, AddBookCopyRequest request) {
        BookDetails book = requireBook(bookId);
        String inventoryNumber = request.inventoryNumber().strip();
        BookCopy copy = new BookCopy(bookId, UUID.randomUUID(), inventoryNumber, BookCopyStatus.AVAILABLE,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        if (!inventoryNumbers.claim(inventoryNumber, bookId, copy.bookCopyId())) {
            throw new ConflictException("Inventory number " + inventoryNumber + " is already in use");
        }
        try {
            newCopies.insert(copy);
        } catch (RuntimeException e) {
            inventoryNumbers.release(inventoryNumber, bookId, copy.bookCopyId());
            throw e;
        }
        log.info("Added copy {} ({}) of book {}", copy.bookCopyId(), inventoryNumber, bookId);
        refreshHoldings(book, copy);
        return BookCopyDetails.from(copy);
    }

    /** Every copy of a book, withdrawn ones included, sorted by inventory number. */
    public List<BookCopyDetails> listBookCopies(UUID bookId) {
        requireBook(bookId);
        return copies.findByBookId(bookId).stream()
                .map(BookCopyDetails::from)
                .sorted(Comparator.comparing(BookCopyDetails::inventoryNumber))
                .toList();
    }

    /**
     * Changes a copy's status by hand, e.g. to mark it as damaged or put it back on the shelf. Anything to do
     * with {@link BookCopyStatus#LENT} is refused, because only lending and returning get to touch that.
     */
    public BookCopyDetails changeStatus(UUID bookId, UUID bookCopyId, BookCopyStatus newStatus) {
        if (newStatus == BookCopyStatus.LENT) {
            throw new ConflictException("A copy only becomes LENT when it's lent to a member");
        }
        BookDetails book = requireBook(bookId);
        BookCopy copy = requireCopy(bookId, bookCopyId);
        return BookCopyDetails.from(applyStatus(book, copy, newStatus));
    }

    /**
     * This is what deleting a copy means here. It's marked as withdrawn, so it stops counting in the holdings,
     * but its record stays around. Withdrawing a copy that's already withdrawn does nothing.
     */
    public void withdraw(UUID bookId, UUID bookCopyId) {
        BookDetails book = requireBook(bookId);
        BookCopy copy = requireCopy(bookId, bookCopyId);
        applyStatus(book, copy, BookCopyStatus.WITHDRAWN);
    }

    private BookCopy applyStatus(BookDetails book, BookCopy copy, BookCopyStatus newStatus) {
        if (copy.status() == newStatus) {
            return copy;
        }
        if (copy.status() == BookCopyStatus.LENT) {
            throw new ConflictException("Book copy " + copy.inventoryNumber()
                    + " is lent out. Its status changes back when it's returned");
        }
        if (!statusChanges.change(copy, newStatus)) {
            throw new ConflictException("Book copy " + copy.inventoryNumber()
                    + " was changed by someone else at the same time. Try again");
        }
        log.info("Book copy {} ({}) went from {} to {}", copy.bookCopyId(), copy.inventoryNumber(),
                copy.status(), newStatus);
        BookCopy updated = copy.withStatus(newStatus);
        refreshHoldings(book, updated);
        return updated;
    }

    private BookDetails requireBook(UUID bookId) {
        return books.findBook(bookId).orElseThrow(() -> new NotFoundException("No book with id " + bookId));
    }

    private BookCopy requireCopy(UUID bookId, UUID bookCopyId) {
        return copies.findById(BasicMapId.id("bookId", bookId).with("bookCopyId", bookCopyId))
                .orElseThrow(() -> new NotFoundException("Book " + bookId + " has no copy with id " + bookCopyId));
    }

    private void refreshHoldings(BookDetails book, BookCopy changedCopy) {
        try {
            holdingsRefresher.refresh(book);
        } catch (RuntimeException e) {
            // The copy itself is already saved, so we don't fail the request. A retry wouldn't help anyway:
            // adding the copy again would only get "number already in use". The holdings get fixed the next
            // time any copy of this book changes.
            log.warn("Book copy {} was saved but the holdings for book {} couldn't be updated",
                    changedCopy.bookCopyId(), book.bookId(), e);
        }
    }
}
