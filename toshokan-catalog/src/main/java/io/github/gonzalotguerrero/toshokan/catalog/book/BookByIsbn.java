package io.github.gonzalotguerrero.toshokan.catalog.book;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.UUID;

/**
 * Maps an ISBN to the book that has it. We only ever insert here with IF NOT EXISTS, which is how two books
 * never end up sharing an ISBN.
 */
@Table("books_by_isbn")
record BookByIsbn(
        @PrimaryKey("isbn") String isbn,
        @Column("book_id") UUID bookId
) {
}
