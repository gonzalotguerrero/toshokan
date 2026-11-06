package io.github.gonzalotguerrero.toshokan.members.member;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A library member. Email and phone are both optional, but every member has at least one of them, so the
 * library can always get in touch about a late book.
 */
@Table("members")
public record Member(
        @PrimaryKey("member_id") UUID memberId,
        @Column("card_number") String cardNumber,
        @Column("full_name") String fullName,
        @Column("email") String email,
        @Column("phone") String phone,
        @Column("status") MemberStatus status,
        // When the member signed up with the library
        @Column("joined_at") Instant joinedAt
) {

    public Member withContactDetails(String newFullName, String newEmail, String newPhone) {
        return new Member(memberId, cardNumber, newFullName, newEmail, newPhone, status, joinedAt);
    }

    public Member withStatus(MemberStatus newStatus) {
        return new Member(memberId, cardNumber, fullName, email, phone, newStatus, joinedAt);
    }
}
