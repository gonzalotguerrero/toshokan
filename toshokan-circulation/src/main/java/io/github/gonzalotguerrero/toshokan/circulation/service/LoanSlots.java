package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByMember;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanByMemberRepository;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyLoan;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberLendingState;
import io.github.gonzalotguerrero.toshokan.shared.cassandra.StaleClaims;
import org.springframework.data.cassandra.core.mapping.BasicMapId;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Works out which of a member's loan slots are still taken by a lend in progress (see {@link MemberLendingState}).
 *
 * <p>A slot is reserved before the copy is lent, so for a moment every slot belongs to a loan no copy shows yet.
 * A slot is still pending unless one of these is true:
 * <ul>
 *     <li>A copy lent to the member shows the loan. It already counts through that copy, so counting the slot too
 *     would count the loan twice.</li>
 *     <li>The loan history says the loan was returned. A return writes the history before it clears the copy, so a
 *     loan that's on no copy and has a return date has really ended.</li>
 *     <li>It's older than {@link #PENDING_FOR}. A lend whose copy write hasn't landed by then never will.</li>
 * </ul>
 * The member has to be read before the copies. A loan that shows up on a copy in between was reserved before the
 * member was read, so its slot is there to be matched.
 */
@Component
public class LoanSlots {

    // The same allowance claims get for a create that's still in progress
    public static final Duration PENDING_FOR = StaleClaims.MIN_AGE;

    private final LoanByMemberRepository history;
    private final Clock clock;

    public LoanSlots(LoanByMemberRepository history, Clock clock) {
        this.history = history;
        this.clock = clock;
    }

    /**
     * The member's slots that are still pending, by loan id.
     *
     * @param lentToMember the copies lent to the member, read after {@code member}
     */
    public Map<UUID, Instant> pending(MemberLendingState member, Collection<BookCopyDetails> lentToMember) {
        Set<UUID> onCopies = lentToMember.stream()
                .map(BookCopyDetails::loan)
                .filter(Objects::nonNull)
                .map(BookCopyLoan::loanId)
                .collect(Collectors.toSet());
        Instant cutoff = clock.instant().minus(PENDING_FOR);
        Map<UUID, Instant> pending = new HashMap<>();
        member.loanSlots().forEach((loanId, reservedAt) -> {
            if (!onCopies.contains(loanId) && !reservedAt.isBefore(cutoff)
                    && !wasReturned(member.memberId(), loanId, reservedAt)) {
                pending.put(loanId, reservedAt);
            }
        });
        return pending;
    }

    /** A slot is reserved with the same time the loan is recorded under, so the history row can be read directly. */
    private boolean wasReturned(UUID memberId, UUID loanId, Instant lentAt) {
        return history.findById(BasicMapId.id("memberId", memberId).with("lentAt", lentAt).with("loanId", loanId))
                .map(LoanByMember::returnedAt)
                .isPresent();
    }
}
