package io.github.gonzalotguerrero.toshokan.catalog.model;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A title in the catalog. Books can't be edited after they're created. That's why other tables, like the
 * inventory's book holdings, can safely keep their own copy of these details.
 */
@Table("books")
public record Book(
        @PrimaryKey("book_id") UUID bookId,
        @Column("isbn") String isbn,
        @Column("title") String title,
        @Column("authors") List<String> authors,
        @Column("publisher") String publisher,
        @Column("publication_year") int publicationYear,
        @Column("language") String language,
        @Column("genres") Set<String> genres,
        @Column("added_at") Instant addedAt
) {
}
