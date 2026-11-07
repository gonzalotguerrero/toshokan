package io.github.gonzalotguerrero.toshokan.members.dto;

import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What circulation needs to know about a member to decide on a lend or a deactivation, as of one read.
 *
 * <p>The loan limit spans several copies, so it can't be enforced by any one copy's write. Instead, every lend
 * first reserves a slot on the member, with a conditional write on {@code version}. Two lends (or a lend and a
 * deactivation) that read the same version can't both get their write in, so the rules are always checked
 * against the member as it really is. A slot stops mattering once its copy shows the loan, or once it's old
 * enough that the lend clearly didn't go through.
 *
 * @param version   pass this back unchanged when reserving a slot
 * @param loanSlots lends in progress, by loan id, with the time each was reserved. Never null
 */
public record MemberLendingState(
        UUID memberId,
        String cardNumber,
        MemberStatus status,
        Long version,
        Map<UUID, Instant> loanSlots
) {

    public static MemberLendingState from(Member member) {
        return new MemberLendingState(member.memberId(), member.cardNumber(), member.status(), member.version(),
                member.loanSlots() == null ? Map.of() : Map.copyOf(member.loanSlots()));
    }
}
