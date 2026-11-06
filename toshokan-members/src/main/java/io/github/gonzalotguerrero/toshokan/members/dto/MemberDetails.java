package io.github.gonzalotguerrero.toshokan.members.dto;

import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;

import java.time.Instant;
import java.util.UUID;

/** What the API returns for a member. */
public record MemberDetails(
        UUID memberId,
        String cardNumber,
        String fullName,
        String email,
        String phone,
        MemberStatus status,
        Instant joinedAt
) {

    public static MemberDetails from(Member member) {
        return new MemberDetails(member.memberId(), member.cardNumber(), member.fullName(), member.email(),
                member.phone(), member.status(), member.joinedAt());
    }
}
