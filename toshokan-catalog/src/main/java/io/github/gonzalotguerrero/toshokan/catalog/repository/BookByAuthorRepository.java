package io.github.gonzalotguerrero.toshokan.catalog.repository;

import io.github.gonzalotguerrero.toshokan.catalog.model.BookByAuthor;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/** Only read from. Rows are written by {@link BookWriter}, together with the book. */
public interface BookByAuthorRepository extends CassandraRepository<BookByAuthor, MapId> {

    /** An author's books, sorted by title. */
    Slice<BookByAuthor> findByAuthorKey(String authorKey, Pageable pageable);
}
