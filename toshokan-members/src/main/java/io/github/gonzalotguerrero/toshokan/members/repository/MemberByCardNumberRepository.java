package io.github.gonzalotguerrero.toshokan.members.repository;

import io.github.gonzalotguerrero.toshokan.members.model.MemberByCardNumber;
import org.springframework.data.cassandra.repository.CassandraRepository;

/** Only read from. Writes to this table go through {@link CardNumberClaims}. */
public interface MemberByCardNumberRepository extends CassandraRepository<MemberByCardNumber, String> {
}
