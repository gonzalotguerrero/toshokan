package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberLendingState;
import io.github.gonzalotguerrero.toshokan.members.service.MemberDeactivationGuard;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stops a member from being deactivated while they still have books out, or while a book is being lent to them.
 * A lend that starts after this check reserves a slot and so changes the member's version, which makes the
 * deactivation's own conditional write fail.
 */
@Component
public class LoanCheckingDeactivationGuard implements MemberDeactivationGuard {

    private final BookCopyService copies;
    private final LoanSlots loanSlots;

    public LoanCheckingDeactivationGuard(BookCopyService copies, LoanSlots loanSlots) {
        this.copies = copies;
        this.loanSlots = loanSlots;
    }

    @Override
    public void checkCanDeactivate(MemberLendingState member) {
        List<BookCopyDetails> booksOut = copies.findCopiesLentTo(member.memberId());
        if (!booksOut.isEmpty()) {
            throw new ConflictException("Member " + member.memberId() + " still has "
                    + (booksOut.size() == 1 ? "1 book" : booksOut.size() + " books")
                    + " out. They have to be returned before the member can be deactivated");
        }
        if (!loanSlots.pending(member, booksOut).isEmpty()) {
            throw new ConflictException("A book is being lent to member " + member.memberId()
                    + " right now. Try again in a moment");
        }
    }
}
