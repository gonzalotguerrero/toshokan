package io.github.gonzalotguerrero.toshokan.members.member;

import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.UUID;

public interface MemberRepository extends CassandraRepository<Member, UUID> {
}
