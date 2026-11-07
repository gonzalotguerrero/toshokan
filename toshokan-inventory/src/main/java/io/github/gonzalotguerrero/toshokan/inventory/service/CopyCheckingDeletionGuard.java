package io.github.gonzalotguerrero.toshokan.inventory.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.service.BookDeletionGuard;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyRepository;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookLocks;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Only lets a book be deleted when it has no copies, or only withdrawn ones. It holds the book's lock while it
 * checks and deletes, the same lock that adding a copy and putting one back from withdrawn take, so no copy can
 * come back to life in between.
 */
@Component
public class CopyCheckingDeletionGuard implements BookDeletionGuard {

    private static final Logger log = LoggerFactory.getLogger(CopyCheckingDeletionGuard.class);

    private final BookLocks locks;
    private final BookCopyRepository copies;
    private final BookHoldingsRefresher holdingsRefresher;

    public CopyCheckingDeletionGuard(BookLocks locks, BookCopyRepository copies,
                                     BookHoldingsRefresher holdingsRefresher) {
        this.locks = locks;
        this.copies = copies;
        this.holdingsRefresher = holdingsRefresher;
    }

    @Override
    public void runGuarded(BookDetails book, Runnable deletion) {
        locks.whileLocked(book.bookId(), lease -> {
            long held = copies.findByBookId(book.bookId()).stream()
                    .filter(copy -> copy.status() != BookCopyStatus.WITHDRAWN)
                    .count();
            if (held > 0) {
                String copiesLeft = held == 1
                        ? "1 copy that isn't withdrawn. It has"
                        : held + " copies that aren't withdrawn. They have";
                throw new ConflictException("Book " + book.bookId() + " still has " + copiesLeft
                        + " to be withdrawn before the book can be deleted");
            }
            lease.ensureStillHeld();
            deletion.run();
            removeFromHoldings(book);
            return null;
        });
    }

    /**
     * With no copies held, the book shouldn't be in the holdings anyway. Refreshing makes sure of it, in case an
     * earlier refresh was missed.
     */
    private void removeFromHoldings(BookDetails book) {
        try {
            holdingsRefresher.refresh(book);
        } catch (RuntimeException e) {
            // The book is already deleted, so this isn't worth failing the request over
            log.warn("Couldn't remove deleted book {} from the holdings", book.bookId(), e);
        }
    }
}
