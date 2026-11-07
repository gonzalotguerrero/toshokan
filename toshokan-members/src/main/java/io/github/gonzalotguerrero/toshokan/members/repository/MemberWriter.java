package io.github.gonzalotguerrero.toshokan.members.repository;

import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberDirectoryEntry;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every write to members goes through here.
 *
 * <p>Writes to {@code members} are all conditional: new members use IF NOT EXISTS, and changes use
 * {@code IF version = ?} with the version that was read. The conditional write is what decides whether a change
 * happened. A directory entry can't share a batch with a conditional write, so it's written first, with a plain
 * insert, and the member list checks it against the member when reading (see {@link MemberDirectoryEntry}).
 */
@Component
public class MemberWriter {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();

    private final CassandraOperations cassandra;

    public MemberWriter(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /** Puts the member in the directory under their current name. Writing the same entry again does nothing new. */
    public void addToDirectory(Member member) {
        cassandra.insert(MemberDirectoryEntry.of(member));
    }

    /** Saves a new member. Returns {@code false} if one with this id already exists, in which case nothing changed. */
    public boolean insert(Member member) {
        return cassandra.insert(member, IF_NOT_EXISTS).wasApplied();
    }

    /**
     * Replaces {@code current} with {@code updated}, as long as nobody changed the member since {@code current} was
     * read. Returns {@code false} if someone did, in which case nothing changed. Loan slots are left alone.
     */
    public boolean update(Member current, Member updated) {
        return updateIfVersion(current.memberId(), current.version(),
                "full_name = ?, email = ?, phone = ?, status = ?",
                updated.fullName(), updated.email(), updated.phone(), updated.status().name());
    }

    /**
     * Replaces the member's loan slots with {@code slots}, as long as the member is still at {@code seenVersion}.
     * Returns {@code false} if they aren't, in which case nothing changed.
     */
    public boolean replaceLoanSlots(UUID memberId, Long seenVersion, Map<UUID, Instant> slots) {
        return updateIfVersion(memberId, seenVersion, "loan_slots = ?", slots);
    }

    /**
     * Removes one loan slot. It's conditional on the member existing only because every write here has to be
     * conditional; it doesn't need the version, since dropping a slot can only ever let fewer lends through.
     */
    public void removeLoanSlot(UUID memberId, UUID loanId) {
        cassandra.getCqlOperations().execute("DELETE loan_slots[?] FROM members WHERE member_id = ? IF EXISTS",
                loanId, memberId);
    }

    /** Runs {@code UPDATE members SET <assignments>, version = <next> WHERE member_id = ? IF version = <seen>}. */
    private boolean updateIfVersion(UUID memberId, Long seenVersion, String assignments, Object... assignedValues) {
        // Members saved before versions existed have no version at all, and CQL compares that with "= null"
        String versionCondition = seenVersion == null ? "IF version = null" : "IF version = ?";
        String cql = "UPDATE members SET " + assignments + ", version = ? WHERE member_id = ? " + versionCondition;
        List<Object> values = new ArrayList<>(Arrays.asList(assignedValues));  // Email and phone may be null
        values.add((seenVersion == null ? 0 : seenVersion) + 1);
        values.add(memberId);
        if (seenVersion != null) {
            values.add(seenVersion);
        }
        return cassandra.getCqlOperations().execute(cql, values.toArray());
    }
}
