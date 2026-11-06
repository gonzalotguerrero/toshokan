package io.github.gonzalotguerrero.toshokan.members.repository;

import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberDirectoryEntry;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

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
     * read. Returns {@code false} if someone did, in which case nothing changed.
     */
    public boolean update(Member current, Member updated) {
        // Members saved before versions existed have no version at all, and CQL compares that with "= null"
        String versionCondition = current.version() == null ? "IF version = null" : "IF version = ?";
        String cql = "UPDATE members SET full_name = ?, email = ?, phone = ?, status = ?, version = ?"
                + " WHERE member_id = ? " + versionCondition;
        Object[] values = current.version() == null
                ? new Object[]{updated.fullName(), updated.email(), updated.phone(), updated.status().name(),
                        current.nextVersion(), current.memberId()}
                : new Object[]{updated.fullName(), updated.email(), updated.phone(), updated.status().name(),
                        current.nextVersion(), current.memberId(), current.version()};
        return cassandra.getCqlOperations().execute(cql, values);
    }
}
