package io.github.gonzalotguerrero.toshokan.catalog.repository;

import io.github.gonzalotguerrero.toshokan.catalog.model.BookByTitle;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

/** Only read from. Rows are written by {@link BookWriter}, together with the book. */
public interface BookByTitleRepository extends CassandraRepository<BookByTitle, MapId> {

    /** The books with this title, newest edition first. */
    Slice<BookByTitle> findByTitleKey(String titleKey, Pageable pageable);
}
