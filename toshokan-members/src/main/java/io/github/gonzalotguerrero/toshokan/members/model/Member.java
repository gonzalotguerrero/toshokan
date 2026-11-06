package io.github.gonzalotguerrero.toshokan.members.model;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A library member. Email and phone are both optional, but every member has at least one of them, so the
 * library can always get in touch about a late book.
 *
 * <p>Every change to a member is a conditional write on {@code version} (see
 * {@link io.github.gonzalotguerrero.toshokan.members.repository.MemberWriter}), so two changes made at the same
 * time can't silently overwrite each other.
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
        @Column("joined_at") Instant joinedAt,
        // Goes up by one with every change. Members saved before versions existed have none, which counts as 0
        @Column("version") Long version
) {

    /** A brand new member, at version 1. */
    public static Member newMember(UUID memberId, String cardNumber, String fullName, String email, String phone,
                                   Instant joinedAt) {
        return new Member(memberId, cardNumber, fullName, email, phone, MemberStatus.ACTIVE, joinedAt, 1L);
    }

    /** The version the next change will have. */
    public long nextVersion() {
        return (version == null ? 0 : version) + 1;
    }

    public Member withContactDetails(String newFullName, String newEmail, String newPhone) {
        return new Member(memberId, cardNumber, newFullName, newEmail, newPhone, status, joinedAt, version);
    }

    public Member withStatus(MemberStatus newStatus) {
        return new Member(memberId, cardNumber, fullName, email, phone, newStatus, joinedAt, version);
    }
}
