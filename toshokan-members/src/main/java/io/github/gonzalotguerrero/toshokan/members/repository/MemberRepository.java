package io.github.gonzalotguerrero.toshokan.members.repository;

import io.github.gonzalotguerrero.toshokan.members.model.Member;
import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.UUID;

public interface MemberRepository extends CassandraRepository<Member, UUID> {
}
