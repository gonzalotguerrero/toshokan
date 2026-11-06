package io.github.gonzalotguerrero.toshokan.catalog.repository;

import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.UUID;

public interface BookRepository extends CassandraRepository<Book, UUID> {
}
