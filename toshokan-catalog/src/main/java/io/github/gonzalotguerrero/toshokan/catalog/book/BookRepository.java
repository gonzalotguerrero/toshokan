package io.github.gonzalotguerrero.toshokan.catalog.book;

import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.UUID;

public interface BookRepository extends CassandraRepository<Book, UUID> {
}
