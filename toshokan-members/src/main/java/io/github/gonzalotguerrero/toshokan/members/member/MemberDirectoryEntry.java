package io.github.gonzalotguerrero.toshokan.members.member;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A member as they appear in the list of all members, sorted by name regardless of case.
 *
 * <p>Like the book holdings, every row shares the same {@code directory_partition}
 * ({@link #DIRECTORY_PARTITION}), so the whole list is one partition that Cassandra keeps sorted for us. A single
 * library's members fit in one partition comfortably.
 *
 * <p>It's a full copy of the member, so listing doesn't need a second read per row. Because the name is part of
 * the key, renaming a member means deleting their old row and writing a new one.
 */
@Table("member_directory")
public record MemberDirectoryEntry(

        @PrimaryKeyColumn(name = "directory_partition", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        int directoryPartition,

        @PrimaryKeyColumn(name = "sort_name", ordinal = 1, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
        String sortName,

        @PrimaryKeyColumn(name = "member_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
        UUID memberId,

        @Column("card_number") String cardNumber,
        @Column("full_name") String fullName,
        @Column("email") String email,
        @Column("phone") String phone,
        @Column("status") MemberStatus status,
        @Column("joined_at") Instant joinedAt
) {

    public static final int DIRECTORY_PARTITION = 0;

    public static String sortKeyFor(String fullName) {
        return fullName.strip().toLowerCase(Locale.ROOT);
    }

    public static MemberDirectoryEntry of(Member member) {
        return new MemberDirectoryEntry(DIRECTORY_PARTITION, sortKeyFor(member.fullName()), member.memberId(),
                member.cardNumber(), member.fullName(), member.email(), member.phone(), member.status(),
                member.joinedAt());
    }
}
