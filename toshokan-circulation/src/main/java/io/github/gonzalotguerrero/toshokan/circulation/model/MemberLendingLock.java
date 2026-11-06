package io.github.gonzalotguerrero.toshokan.circulation.model;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * A row here means something is lending to this member (or deactivating them) right now. See
 * {@link io.github.gonzalotguerrero.toshokan.circulation.repository.MemberLendingLocks}.
 */
@Table("member_lending_locks")
public record MemberLendingLock(
        @PrimaryKey("member_id") UUID memberId,
        // Tells our own lock apart from someone else's, so we never release a lock we don't hold
        @Column("lock_id") UUID lockId
) {
}
