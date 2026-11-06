package io.github.gonzalotguerrero.toshokan.members.repository;

import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.github.gonzalotguerrero.toshokan.members.model.MemberByCardNumber;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.DeleteOptions;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes sure no two members share a card number. Claims use IF NOT EXISTS, so if two requests go for the same
 * number at once, only one gets it.
 *
 * <p>Releasing and taking over a claim are conditional on the member that holds it, so nobody can free or steal a
 * claim that has already moved on. That also keeps every write here conditional: a plain write would be
 * timestamped by our clock while the claim was timestamped by Cassandra's, and if Cassandra's clock were ahead,
 * the plain write would count as older and do nothing.
 */
@Component
public class CardNumberClaims {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();

    private final CassandraOperations cassandra;

    public CardNumberClaims(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Which member holds a card number, and since when. */
    public record Claim(UUID memberId, Instant claimedAt) {
    }

    /** Returns {@code false} if another member already has this card number. */
    public boolean claim(String cardNumber, UUID memberId) {
        return cassandra.insert(new MemberByCardNumber(cardNumber, memberId), IF_NOT_EXISTS).wasApplied();
    }

    /** The current claim on a card number, or empty if nobody holds it. */
    public Optional<Claim> currentClaim(String cardNumber) {
        // The write time is when Cassandra stored the claim, in microseconds
        Row row = cassandra.execute(SimpleStatement.newInstance(
                "SELECT member_id, writetime(member_id) AS claimed_at FROM members_by_card_number"
                        + " WHERE card_number = ?", cardNumber)).one();
        if (row == null) {
            return Optional.empty();
        }
        Instant claimedAt = Instant.EPOCH.plus(row.getLong("claimed_at"), ChronoUnit.MICROS);
        return Optional.of(new Claim(row.getUuid("member_id"), claimedAt));
    }

    /** Moves the claim from a member that doesn't exist to a new one. Returns {@code false} if it moved already. */
    public boolean takeOver(String cardNumber, UUID staleMemberId, UUID newMemberId) {
        return cassandra.getCqlOperations().execute(
                "UPDATE members_by_card_number SET member_id = ? WHERE card_number = ? IF member_id = ?",
                newMemberId, cardNumber, staleMemberId);
    }

    /** Frees the card number, but only if this member still holds it. Used when saving the member didn't work. */
    public void release(String cardNumber, UUID memberId) {
        DeleteOptions onlyOurClaim = DeleteOptions.builder()
                // The column name, not the property name: Spring passes IF conditions through untranslated
                .ifCondition(Criteria.where("member_id").is(memberId))
                .build();
        cassandra.delete(new MemberByCardNumber(cardNumber, memberId), onlyOurClaim);
    }
}
