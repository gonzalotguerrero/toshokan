package io.github.gonzalotguerrero.toshokan.members.repository;

import com.datastax.oss.driver.api.core.cql.BatchType;
import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberDirectoryEntry;
import org.springframework.data.cassandra.core.CassandraBatchOperations;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Saves a member and their directory entry together, in one logged batch. Cassandra writes the whole batch down
 * before applying any of it, so either every row ends up written or none does. The directory can't drift away
 * from the member record, even if something fails halfway through.
 *
 * <p>Nulls are written on purpose: that's how an email or phone that was cleared actually gets removed.
 */
@Component
public class MemberWriter {

    private static final InsertOptions INSERT_NULLS = InsertOptions.builder().withInsertNulls().build();

    private final CassandraOperations cassandra;

    public MemberWriter(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    /**
     * @param before the member as they were, or {@code null} for a new member
     * @param after  the member as they should be saved
     */
    public void save(Member before, Member after) {
        MemberDirectoryEntry entry = MemberDirectoryEntry.of(after);
        CassandraBatchOperations batch = cassandra.batchOps(BatchType.LOGGED).insert(List.of(after, entry), INSERT_NULLS);
        if (before != null) {
            MemberDirectoryEntry oldEntry = MemberDirectoryEntry.of(before);
            if (!oldEntry.sortName().equals(entry.sortName())) {
                // The name is part of the directory key, so a rename leaves the old row behind unless we delete it
                batch.delete(oldEntry);
            }
        }
        batch.execute();
    }
}
