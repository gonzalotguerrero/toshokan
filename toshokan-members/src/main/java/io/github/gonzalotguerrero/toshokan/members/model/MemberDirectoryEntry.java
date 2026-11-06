package io.github.gonzalotguerrero.toshokan.members.model;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.Locale;
import java.util.UUID;

/**
 * Where a member sits in the list of all members, sorted by name regardless of case.
 *
 * <p>Like the book holdings, every row shares the same {@code directory_partition} ({@link #DIRECTORY_PARTITION}),
 * so the whole list is one partition that Cassandra keeps sorted for us. A single library's members fit in one
 * partition comfortably.
 *
 * <p>An entry only gives the order. It's written before the member's own (conditional) write, so it can't be
 * trusted on its own: the list shows an entry only if the member's current name still gives this
 * {@code sort_name}, and it shows the member's details from {@code members}. Entries left over from a rename, or
 * from a change that lost a race, are simply skipped. That's also why renaming never deletes the old entry: the
 * delete could arrive late and remove an entry the member needs again after renaming back.
 */
@Table("member_directory")
public record MemberDirectoryEntry(

        @PrimaryKeyColumn(name = "directory_partition", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        int directoryPartition,

        @PrimaryKeyColumn(name = "sort_name", ordinal = 1, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
        String sortName,

        @PrimaryKeyColumn(name = "member_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
        UUID memberId
) {

    public static final int DIRECTORY_PARTITION = 0;

    public static String sortKeyFor(String fullName) {
        return fullName.strip().toLowerCase(Locale.ROOT);
    }

    public static MemberDirectoryEntry of(Member member) {
        return new MemberDirectoryEntry(DIRECTORY_PARTITION, sortKeyFor(member.fullName()), member.memberId());
    }

    /** Whether this entry is where {@code member} belongs in the list right now. */
    public boolean isCurrentFor(Member member) {
        return member.memberId().equals(memberId) && sortKeyFor(member.fullName()).equals(sortName);
    }
}
