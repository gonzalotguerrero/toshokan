package io.github.gonzalotguerrero.toshokan.members.repository;

import io.github.gonzalotguerrero.toshokan.members.model.MemberByCardNumber;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.DeleteOptions;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Makes sure no two members share a card number. Claims use IF NOT EXISTS, so if two requests go for the same
 * number at once, only one gets it.
 *
 * <p>Releasing a claim is conditional too (IF EXISTS). A plain delete would be timestamped by our clock while the
 * claim was timestamped by Cassandra's, and if Cassandra's clock were ahead, the delete would count as older than
 * the claim and do nothing.
 */
@Component
public class CardNumberClaims {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();
    private static final DeleteOptions IF_EXISTS = DeleteOptions.builder().withIfExists().build();

    private final CassandraOperations cassandra;

    public CardNumberClaims(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Returns {@code false} if another member already has this card number. */
    public boolean claim(String cardNumber, UUID memberId) {
        return cassandra.insert(new MemberByCardNumber(cardNumber, memberId), IF_NOT_EXISTS).wasApplied();
    }

    /** Frees the card number again. Used when the claim worked but saving the member didn't. */
    public void release(String cardNumber, UUID memberId) {
        cassandra.delete(new MemberByCardNumber(cardNumber, memberId), IF_EXISTS);
    }
}
