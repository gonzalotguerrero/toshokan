package io.github.gonzalotguerrero.toshokan.members.member;

import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface MemberDirectoryRepository extends CassandraRepository<MemberDirectoryEntry, MapId> {

    /** All members, sorted by name. */
    Slice<MemberDirectoryEntry> findByDirectoryPartition(int directoryPartition, Pageable pageable);
}
