package io.github.gonzalotguerrero.toshokan.inventory.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.service.BookService;
import io.github.gonzalotguerrero.toshokan.inventory.dto.AddBookCopyRequest;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyByInventoryNumberRepository;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyRepository;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyStatusChanges;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookLocks;
import io.github.gonzalotguerrero.toshokan.inventory.repository.InventoryNumberClaims;
import io.github.gonzalotguerrero.toshokan.inventory.repository.NewBookCopyInserter;
import io.github.gonzalotguerrero.toshokan.shared.cassandra.StaleClaims;
import io.github.gonzalotguerrero.toshokan.shared.cassandra.WriteOutcomes;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.cassandra.core.mapping.BasicMapId;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookCopyService {

    private static final Logger log = LoggerFactory.getLogger(BookCopyService.class);

    private final BookService books;
    private final BookCopyRepository copies;
    private final BookCopyByInventoryNumberRepository copiesByInventoryNumber;
    private final InventoryNumberClaims inventoryNumbers;
    private final NewBookCopyInserter newCopies;
    private final BookCopyStatusChanges statusChanges;
    private final BookHoldingsRefresher holdingsRefresher;
    private final BookLocks locks;
    private final Clock clock;

    public BookCopyService(BookService books, BookCopyRepository copies,
                           BookCopyByInventoryNumberRepository copiesByInventoryNumber,
                           InventoryNumberClaims inventoryNumbers, NewBookCopyInserter newCopies,
                           BookCopyStatusChanges statusChanges, BookHoldingsRefresher holdingsRefresher,
                           BookLocks locks, Clock clock) {
        this.books = books;
        this.copies = copies;
        this.copiesByInventoryNumber = copiesByInventoryNumber;
        this.inventoryNumbers = inventoryNumbers;
        this.newCopies = newCopies;
        this.statusChanges = statusChanges;
        this.holdingsRefresher = holdingsRefresher;
        this.locks = locks;
        this.clock = clock;
    }

    /**
     * Adds a new copy of a book, on the shelf and ready to be lent. If it's the book's first copy, the book
     * appears in the holdings; otherwise its counts go up. It holds the book's lock, so the book can't be deleted
     * while the copy is being added.
     */
    public BookCopyDetails addBookCopy(UUID bookId, AddBookCopyRequest request) {
        return locks.whileLocked(bookId, () -> insertCopy(bookId, request));
    }

    private BookCopyDetails insertCopy(UUID bookId, AddBookCopyRequest request) {
        BookDetails book = requireBook(bookId);
        String inventoryNumber = request.inventoryNumber().strip();
        BookCopy copy = BookCopy.newCopy(bookId, UUID.randomUUID(), inventoryNumber, now());

        if (!claimInventoryNumber(inventoryNumber, bookId, copy.bookCopyId())) {
            throw new ConflictException("Inventory number " + inventoryNumber + " is already in use");
        }
        boolean inserted;
        try {
            inserted = newCopies.insert(copy);
        } catch (RuntimeException e) {
            // If the copy might still get saved (a timeout, say), the number has to stay its own. Should the copy
            // never arrive, the claim goes stale and the next copy with this number takes it over
            if (WriteOutcomes.definitelyNotApplied(e)) {
                inventoryNumbers.release(inventoryNumber, bookId, copy.bookCopyId());
            }
            throw e;
        }
        if (!inserted) {
            inventoryNumbers.release(inventoryNumber, bookId, copy.bookCopyId());
            throw new IllegalStateException("A book copy with id " + copy.bookCopyId() + " already exists");
        }
        log.info("Added copy {} ({}) of book {}", copy.bookCopyId(), inventoryNumber, bookId);
        refreshHoldings(book, copy);
        return details(copy);
    }

    /** Every copy of a book, withdrawn ones included, sorted by inventory number. */
    public List<BookCopyDetails> listBookCopies(UUID bookId) {
        requireBook(bookId);
        return copies.findByBookId(bookId).stream()
                .map(this::details)
                .sorted(Comparator.comparing(BookCopyDetails::inventoryNumber))
                .toList();
    }

    /**
     * Changes a copy's status by hand, e.g. to mark it as damaged or put it back on the shelf. Anything to do
     * with {@link BookCopyStatus#LENT} is refused, because only lending and returning get to touch that.
     *
     * <p>Bringing a copy back from withdrawn holds the book's lock, like adding a copy does, so it can't happen
     * while the book is being deleted. The book is read again under the lock, in case it was deleted just before.
     */
    public BookCopyDetails changeStatus(UUID bookId, UUID bookCopyId, BookCopyStatus newStatus) {
        if (newStatus == BookCopyStatus.LENT) {
            throw new ConflictException("A copy only becomes LENT when it's lent to a member");
        }
        BookDetails book = requireBook(bookId);
        BookCopy copy = requireCopy(bookId, bookCopyId);
        if (copy.status() == BookCopyStatus.WITHDRAWN && newStatus != BookCopyStatus.WITHDRAWN) {
            return locks.whileLocked(bookId, () -> details(applyStatus(requireBook(bookId), copy, newStatus)));
        }
        return details(applyStatus(book, copy, newStatus));
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

    /** Used by circulation to find the copy behind the number on a book's label. */
    public Optional<BookCopyDetails> findBookCopyByInventoryNumber(String inventoryNumber) {
        return copiesByInventoryNumber.findById(inventoryNumber.strip())
                .flatMap(byNumber -> copies.findById(copyKey(byNumber.bookId(), byNumber.bookCopyId())))
                .map(this::details);
    }

    public Optional<BookCopyDetails> findBookCopy(UUID bookId, UUID bookCopyId) {
        return copies.findById(copyKey(bookId, bookCopyId)).map(this::details);
    }

    /** Every copy that's lent out right now, to whoever. */
    public List<BookCopyDetails> findLentCopies() {
        return copies.findByStatus(BookCopyStatus.LENT).stream().map(this::details).toList();
    }

    /** The copies a member has out right now. */
    public List<BookCopyDetails> findCopiesLentTo(UUID memberId) {
        return copies.findByMemberId(memberId).stream().map(this::details).toList();
    }

    /**
     * Lends an AVAILABLE copy to a member. The checks about the member (limits, overdue books) are circulation's
     * job; this only makes sure the copy itself can go out, and that nobody else lends it at the same moment.
     * Circulation picks the loan id and time, because it records them in the loan history before calling this.
     */
    public BookCopyDetails markLent(UUID bookId, UUID bookCopyId, UUID memberId, UUID loanId, Instant lentAt,
                                   LocalDate dueDate) {
        BookDetails book = requireBook(bookId);
        BookCopy copy = requireCopy(bookId, bookCopyId);
        if (copy.status() != BookCopyStatus.AVAILABLE) {
            throw new ConflictException("Book copy " + copy.inventoryNumber() + " can't be lent because it's "
                    + copy.status());
        }
        BookCopy lent = copy.lentTo(loanId, memberId, lentAt, dueDate);
        writeChange(copy, lent);
        log.info("Lent book copy {} ({}) to member {} until {}", copy.bookCopyId(), copy.inventoryNumber(),
                memberId, dueDate);
        refreshHoldings(book, lent);
        return details(lent);
    }

    /**
     * Puts a lent copy back on the shelf. {@code loanId} and {@code renewalsSeen} are the loan the caller saw (and
     * wrote into the loan history), so if it changed in between, the return fails instead of going through with
     * details that no longer match.
     */
    public BookCopyDetails markReturned(UUID bookId, UUID bookCopyId, UUID loanId, int renewalsSeen) {
        BookDetails book = requireBook(bookId);
        BookCopy copy = requireLoan(bookId, bookCopyId, loanId);
        if (copy.renewals() != renewalsSeen) {
            throw changedMeanwhile(copy);
        }
        BookCopy returned = copy.returned();
        writeChange(copy, returned);
        log.info("Book copy {} ({}) was returned by member {}", copy.bookCopyId(), copy.inventoryNumber(),
                copy.memberId());
        refreshHoldings(book, returned);
        return details(returned);
    }

    /**
     * Moves a loan's due date and counts one more renewal. {@code loanId} and {@code renewalsSeen} are what the
     * caller checked its rules against, so if the loan changed in between, this fails instead of renewing twice.
     */
    public BookCopyDetails extendLoan(UUID bookId, UUID bookCopyId, UUID loanId, int renewalsSeen,
                                      LocalDate newDueDate) {
        BookCopy copy = requireLoan(bookId, bookCopyId, loanId);
        if (copy.renewals() != renewalsSeen) {
            throw changedMeanwhile(copy);
        }
        BookCopy renewed = copy.renewedUntil(newDueDate);
        writeChange(copy, renewed);
        log.info("Renewed loan of book copy {} ({}) until {}", copy.bookCopyId(), copy.inventoryNumber(),
                newDueDate);
        // Renewing doesn't change any counts, so the holdings stay as they are
        return details(renewed);
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
            throw changedMeanwhile(copy);
        }
        log.info("Book copy {} ({}) went from {} to {}", copy.bookCopyId(), copy.inventoryNumber(),
                copy.status(), newStatus);
        BookCopy updated = copy.withStatus(newStatus);
        refreshHoldings(book, updated);
        return updated;
    }

    /**
     * Claims an inventory number for a new copy. If another copy holds it, the claim is only taken over when it's
     * stale (see {@link StaleClaims}), which means a create that failed long enough ago.
     */
    private boolean claimInventoryNumber(String inventoryNumber, UUID bookId, UUID bookCopyId) {
        if (inventoryNumbers.claim(inventoryNumber, bookId, bookCopyId)) {
            return true;
        }
        Optional<InventoryNumberClaims.Claim> current = inventoryNumbers.currentClaim(inventoryNumber);
        if (current.isEmpty()) {
            // Freed between our claim and this read, so it's worth one more try
            return inventoryNumbers.claim(inventoryNumber, bookId, bookCopyId);
        }
        InventoryNumberClaims.Claim claim = current.get();
        boolean ownerExists = copies.existsById(copyKey(claim.bookId(), claim.bookCopyId()));
        if (StaleClaims.isStale(claim.claimedAt(), ownerExists, clock)
                && inventoryNumbers.takeOver(inventoryNumber, claim, bookId, bookCopyId)) {
            log.info("Took over inventory number {} from copy {}, which doesn't exist", inventoryNumber,
                    claim.bookCopyId());
            return true;
        }
        return false;
    }

    private BookDetails requireBook(UUID bookId) {
        return books.findBook(bookId).orElseThrow(() -> new NotFoundException("No book with id " + bookId));
    }

    private BookCopy requireCopy(UUID bookId, UUID bookCopyId) {
        return copies.findById(copyKey(bookId, bookCopyId))
                .orElseThrow(() -> new NotFoundException("Book " + bookId + " has no copy with id " + bookCopyId));
    }

    private BookCopy requireLoan(UUID bookId, UUID bookCopyId, UUID loanId) {
        BookCopy copy = requireCopy(bookId, bookCopyId);
        if (copy.loanId() == null) {
            throw new ConflictException("Book copy " + copy.inventoryNumber() + " isn't lent out");
        }
        if (!copy.loanId().equals(loanId)) {
            throw changedMeanwhile(copy);
        }
        return copy;
    }

    private void writeChange(BookCopy current, BookCopy updated) {
        if (!statusChanges.replace(current, updated)) {
            throw changedMeanwhile(current);
        }
    }

    private static ConflictException changedMeanwhile(BookCopy copy) {
        return new ConflictException("Book copy " + copy.inventoryNumber()
                + " was changed by someone else at the same time. Try again");
    }

    private static MapId copyKey(UUID bookId, UUID bookCopyId) {
        return BasicMapId.id("bookId", bookId).with("bookCopyId", bookCopyId);
    }

    private BookCopyDetails details(BookCopy copy) {
        return BookCopyDetails.from(copy, LocalDate.now(clock));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
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
