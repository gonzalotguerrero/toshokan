package io.github.gonzalotguerrero.toshokan.catalog.repository;

import io.github.gonzalotguerrero.toshokan.catalog.model.BookByIsbn;
import org.springframework.data.cassandra.repository.CassandraRepository;

/** Only read from, to look a book up by its ISBN. Claims and releases go through {@link IsbnClaims}. */
public interface BookByIsbnRepository extends CassandraRepository<BookByIsbn, String> {
}
