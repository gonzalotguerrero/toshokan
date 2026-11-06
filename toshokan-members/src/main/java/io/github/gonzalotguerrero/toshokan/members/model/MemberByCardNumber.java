package io.github.gonzalotguerrero.toshokan.members.model;

import io.github.gonzalotguerrero.toshokan.members.repository.CardNumberClaims;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * Which member a card number belongs to. It's what the desk uses to find someone from their card, and the row
 * doubles as the claim that keeps card numbers unique (see {@link CardNumberClaims}).
 */
@Table("members_by_card_number")
public record MemberByCardNumber(
        @PrimaryKey("card_number") String cardNumber,
        @Column("member_id") UUID memberId
) {
}
