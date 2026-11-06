package io.github.gonzalotguerrero.toshokan.members.member;

import org.springframework.data.cassandra.repository.CassandraRepository;

/** Only read from. Writes to this table go through {@link CardNumberClaims}. */
public interface MemberByCardNumberRepository extends CassandraRepository<MemberByCardNumber, String> {
}
