package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.circulation.repository.MemberLendingLocks;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.members.service.MemberDeactivationGuard;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stops a member from being deactivated while they still have books out. It takes the same per-member lock as
 * lending, so a loan can't slip in between the check and the deactivation.
 */
@Component
public class LoanCheckingDeactivationGuard implements MemberDeactivationGuard {

    private final MemberLendingLocks locks;
    private final BookCopyService copies;

    public LoanCheckingDeactivationGuard(MemberLendingLocks locks, BookCopyService copies) {
        this.locks = locks;
        this.copies = copies;
    }

    @Override
    public void runGuarded(UUID memberId, Runnable deactivation) {
        locks.whileLocked(memberId, () -> {
            int booksOut = copies.findCopiesLentTo(memberId).size();
            if (booksOut > 0) {
                throw new ConflictException("Member " + memberId + " still has "
                        + (booksOut == 1 ? "1 book" : booksOut + " books")
                        + " out. They have to be returned before the member can be deactivated");
            }
            deactivation.run();
            return null;
        });
    }
}
