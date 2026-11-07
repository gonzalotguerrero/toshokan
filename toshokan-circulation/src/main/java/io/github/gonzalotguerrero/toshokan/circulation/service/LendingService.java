package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.service.BookService;
import io.github.gonzalotguerrero.toshokan.circulation.config.LendingProperties;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LendRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanDetails;
import io.github.gonzalotguerrero.toshokan.circulation.dto.OverdueLoan;
import io.github.gonzalotguerrero.toshokan.circulation.dto.RenewalRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.ReturnRequest;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByCopy;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanHistoryWriter;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyLoan;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberLendingState;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import io.github.gonzalotguerrero.toshokan.members.service.MemberService;
import io.github.gonzalotguerrero.toshokan.shared.cassandra.WriteOutcomes;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The rules of the lending desk: who can borrow, how many books, for how long, and how often a loan can be
 * renewed. The copy-level writes are {@link BookCopyService}'s job; this decides whether they should happen.
 *
 * <p>Lends and returns are recorded in the loan history first and only then applied to the copy. If the second
 * step fails, the history row is left behind, but {@link LoanHistoryService} checks every row against the copy, so
 * a row like that never shows up as something that happened.
 *
 * <p>The loan limit and the other member rules are decided by reserving a loan slot on the member before anything
 * else is written (see {@link MemberLendingState}). If two lends for the same member race, only one reservation
 * gets in, and the other request gets a conflict.
 */
@Service
public class LendingService {

    private static final Logger log = LoggerFactory.getLogger(LendingService.class);

    private final MemberService members;
    private final BookService books;
    private final BookCopyService copies;
    private final LoanSlots loanSlots;
    private final LoanHistoryWriter history;
    private final LendingProperties rules;
    private final Clock clock;

    public LendingService(MemberService members, BookService books, BookCopyService copies,
                          LoanSlots loanSlots, LoanHistoryWriter history, LendingProperties rules, Clock clock) {
        this.members = members;
        this.books = books;
        this.copies = copies;
        this.loanSlots = loanSlots;
        this.history = history;
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

        // The member has to be read before their loans: any lend that reserves a slot after this read changes the
        // version, so our own reservation below fails instead of going over the limit
        MemberLendingState state = members.lendingState(member.memberId());
        if (state.status() != MemberStatus.ACTIVE) {
            throw new ConflictException("Member " + cardNumber + " is inactive and can't borrow books");
        }
        List<BookCopyDetails> borrowed = currentLoans(member.memberId());
        Optional<BookCopyDetails> late = borrowed.stream().filter(lent -> lent.loan().overdue()).findFirst();
        if (late.isPresent()) {
            throw new ConflictException("Member " + cardNumber + " has an overdue book ("
                    + late.get().inventoryNumber() + ") and can't borrow more until it's returned");
        }
        Map<UUID, Instant> pending = loanSlots.pending(state, borrowed);
        int taken = borrowed.size() + pending.size();
        if (taken >= rules.maxActiveLoans()) {
            throw new ConflictException("Member " + cardNumber + " already has " + books(taken)
                    + " out, which is the most allowed");
        }
        // The copy's own write checks this again, but checking now saves using up a slot on a lend that can't work
        if (copy.status() != BookCopyStatus.AVAILABLE) {
            throw new ConflictException("Book copy " + copy.inventoryNumber() + " can't be lent because it's "
                    + copy.status());
        }

        LocalDate dueDate = today().plus(rules.loanPeriod());
        UUID loanId = UUID.randomUUID();
        // Cassandra keeps timestamps to the millisecond, so the slot, the copy and the history get the same value
        Instant lentAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!members.reserveLoanSlot(state, pending, loanId, lentAt)) {
            throw new ConflictException("Member " + cardNumber
                    + " was changed by someone else at the same time. Try again");
        }
        try {
            history.save(new LoanByCopy(copy.bookCopyId(), lentAt, loanId, copy.bookId(), copy.inventoryNumber(),
                    member.memberId(), dueDate, 0, null));
        } catch (RuntimeException e) {
            // The copy hasn't been touched, so the lend can't have happened whatever became of this write
            releaseSlot(member.memberId(), loanId);
            throw e;
        }
        try {
            return LoanDetails.from(copies.markLent(copy.bookId(), copy.bookCopyId(), member.memberId(), loanId,
                    lentAt, dueDate));
        } catch (ConflictException | NotFoundException e) {
            // Refused before anything was written to the copy
            releaseSlot(member.memberId(), loanId);
            throw e;
        } catch (RuntimeException e) {
            // After a timeout the copy may still end up lent, so the slot has to stay. If the lend never lands,
            // the slot stops counting once it's older than LoanSlots.PENDING_FOR
            if (WriteOutcomes.definitelyNotApplied(e)) {
                releaseSlot(member.memberId(), loanId);
            }
            throw e;
        }
    }

    /** Takes a copy back. Being late doesn't stop a return, it's what fixes it. */
    public void returnCopy(ReturnRequest request) {
        BookCopyDetails copy = requireCopy(request.inventoryNumber());
        BookCopyLoan loan = requireLoan(copy);
        history.save(new LoanByCopy(copy.bookCopyId(), loan.lentAt(), loan.loanId(), copy.bookId(),
                copy.inventoryNumber(), loan.memberId(), loan.dueDate(), loan.renewals(),
                clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        // Passing the renewals seen makes the return fail if a renewal sneaks in now, so the history row can't
        // end up with an older due date than the one the loan really ended with
        copies.markReturned(copy.bookId(), copy.bookCopyId(), loan.loanId(), loan.renewals());
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

    /**
     * Every loan that's past its due date, the most overdue first, with the member's contact details. Built from the
     * copies themselves, so it's always up to date. A book or member that can't be found is shown with blanks
     * rather than hiding the loan.
     */
    public List<OverdueLoan> overdueLoans() {
        LocalDate today = today();
        Map<UUID, Optional<BookDetails>> booksSeen = new HashMap<>();
        Map<UUID, Optional<MemberDetails>> membersSeen = new HashMap<>();
        return copies.findLentCopies().stream()
                .filter(copy -> copy.loan() != null && copy.loan().overdue())
                .map(copy -> {
                    BookCopyLoan loan = copy.loan();
                    Optional<BookDetails> book = booksSeen.computeIfAbsent(copy.bookId(), books::findBook);
                    Optional<MemberDetails> member = membersSeen.computeIfAbsent(loan.memberId(),
                            members::findMember);
                    return new OverdueLoan(loan.loanId(), copy.bookId(), copy.bookCopyId(), copy.inventoryNumber(),
                            book.map(BookDetails::title).orElse(null), loan.memberId(),
                            member.map(MemberDetails::cardNumber).orElse(null),
                            member.map(MemberDetails::fullName).orElse(null),
                            member.map(MemberDetails::email).orElse(null),
                            member.map(MemberDetails::phone).orElse(null),
                            loan.dueDate(), ChronoUnit.DAYS.between(loan.dueDate(), today));
                })
                .sorted(Comparator.comparingLong(OverdueLoan::daysOverdue).reversed()
                        .thenComparing(OverdueLoan::inventoryNumber))
                .toList();
    }

    private List<BookCopyDetails> currentLoans(UUID memberId) {
        return copies.findCopiesLentTo(memberId).stream().filter(copy -> copy.loan() != null).toList();
    }

    private void releaseSlot(UUID memberId, UUID loanId) {
        try {
            members.releaseLoanSlot(memberId, loanId);
        } catch (RuntimeException e) {
            // The lend has failed already. The slot stops counting on its own once it's old enough
            log.warn("Couldn't release loan slot {} of member {}", loanId, memberId, e);
        }
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
