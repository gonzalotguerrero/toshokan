package io.github.gonzalotguerrero.toshokan.circulation.repository;

import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByMember;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.util.UUID;

/** Only read from. Writes go through {@link LoanHistoryWriter}, which keeps both history tables in step. */
public interface LoanByMemberRepository extends CassandraRepository<LoanByMember, MapId> {

    /** A member's loans, newest first. */
    Slice<LoanByMember> findByMemberId(UUID memberId, Pageable pageable);
}
