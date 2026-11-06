package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.circulation.config.LendingProperties;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LendRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanDetails;
import io.github.gonzalotguerrero.toshokan.circulation.dto.RenewalRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.ReturnRequest;
import io.github.gonzalotguerrero.toshokan.circulation.repository.MemberLendingLocks;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyLoan;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import io.github.gonzalotguerrero.toshokan.members.service.MemberService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The rules of the lending desk: who can borrow, how many books, for how long, and how often a loan can be
 * renewed. The copy-level writes are {@link BookCopyService}'s job; this decides whether they should happen.
 */
@Service
public class LendingService {

    private final MemberService members;
    private final BookCopyService copies;
    private final MemberLendingLocks locks;
    private final LendingProperties rules;
    private final Clock clock;

    public LendingService(MemberService members, BookCopyService copies, MemberLendingLocks locks,
                          LendingProperties rules, Clock clock) {
        this.members = members;
        this.copies = copies;
        this.locks = locks;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * Lends a copy to a member, due back after the loan period. Refused if the member is inactive, has an overdue
     * book, or already has as many books out as allowed.
     */
    public LoanDetails lend(LendRequest request) {
        String cardNumber = request.cardNumber().strip();
        MemberDetails member = members.findMemberByCardNumber(cardNumber)
                .orElseThrow(() -> new NotFoundException("No member with card number " + cardNumber));
        BookCopyDetails copy = requireCopy(request.inventoryNumber());

        return locks.whileLocked(member.memberId(), () -> {
            // Read the member again now that we hold the lock, in case they were deactivated a moment ago
            MemberDetails current = members.getMember(member.memberId());
            if (current.status() != MemberStatus.ACTIVE) {
                throw new ConflictException("Member " + cardNumber + " is inactive and can't borrow books");
            }
            List<BookCopyDetails> borrowed = currentLoans(member.memberId());
            Optional<BookCopyDetails> late = borrowed.stream().filter(lent -> lent.loan().overdue()).findFirst();
            if (late.isPresent()) {
                throw new ConflictException("Member " + cardNumber + " has an overdue book ("
                        + late.get().inventoryNumber() + ") and can't borrow more until it's returned");
            }
            if (borrowed.size() >= rules.maxActiveLoans()) {
                throw new ConflictException("Member " + cardNumber + " already has " + books(borrowed.size())
                        + " out, which is the most allowed");
            }
            LocalDate dueDate = today().plus(rules.loanPeriod());
            return LoanDetails.from(copies.markLent(copy.bookId(), copy.bookCopyId(), member.memberId(), dueDate));
        });
    }

    /** Takes a copy back. Being late doesn't stop a return, it's what fixes it. */
    public void returnCopy(ReturnRequest request) {
        BookCopyDetails copy = requireCopy(request.inventoryNumber());
        BookCopyLoan loan = requireLoan(copy);
        copies.markReturned(copy.bookId(), copy.bookCopyId(), loan.loanId());
    }

    /** Pushes the due date to today plus the loan period. Overdue loans can't be renewed, only returned. */
    public LoanDetails renew(RenewalRequest request) {
        BookCopyDetails copy = requireCopy(request.inventoryNumber());
        BookCopyLoan loan = requireLoan(copy);
        if (loan.overdue()) {
            throw new ConflictException("Book copy " + copy.inventoryNumber()
                    + " is overdue, so it can't be renewed. It has to be returned first");
        }
        if (loan.renewals() >= rules.maxRenewals()) {
            throw new ConflictException("Book copy " + copy.inventoryNumber() + " has already been renewed "
                    + loan.renewals() + " times, which is the most allowed");
        }
        LocalDate newDueDate = today().plus(rules.loanPeriod());
        return LoanDetails.from(copies.extendLoan(copy.bookId(), copy.bookCopyId(), loan.loanId(), loan.renewals(),
                newDueDate));
    }

    /** What a member has out right now, the one due soonest first. */
    public List<LoanDetails> memberLoans(UUID memberId) {
        members.getMember(memberId);
        return currentLoans(memberId).stream()
                .map(LoanDetails::from)
                .sorted(Comparator.comparing(LoanDetails::dueDate).thenComparing(LoanDetails::inventoryNumber))
                .toList();
    }

    private List<BookCopyDetails> currentLoans(UUID memberId) {
        return copies.findCopiesLentTo(memberId).stream().filter(copy -> copy.loan() != null).toList();
    }

    private BookCopyDetails requireCopy(String inventoryNumber) {
        String number = inventoryNumber.strip();
        return copies.findBookCopyByInventoryNumber(number)
                .orElseThrow(() -> new NotFoundException("No book copy with inventory number " + number));
    }

    private static BookCopyLoan requireLoan(BookCopyDetails copy) {
        if (copy.loan() == null) {
            throw new ConflictException("Book copy " + copy.inventoryNumber() + " isn't lent out");
        }
        return copy.loan();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String books(int count) {
        return count == 1 ? "1 book" : count + " books";
    }
}
