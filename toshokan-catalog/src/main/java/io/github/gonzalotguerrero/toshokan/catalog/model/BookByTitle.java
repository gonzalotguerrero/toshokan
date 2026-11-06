package io.github.gonzalotguerrero.toshokan.catalog.model;

import io.github.gonzalotguerrero.toshokan.catalog.service.BookTextNormalizer;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Books grouped by title, for searching by exact title. Every book with the same title (ignoring case, spacing and
 * character width) shares a partition, newest edition first.
 *
 * <p>The row holds the whole book, so a search doesn't have to look each one up again. That's only safe because
 * books never change after they're created, and the row is written in the same logged batch as the book itself.
 */
@Table("books_by_title")
public record BookByTitle(

        @PrimaryKeyColumn(name = "title_key", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        String titleKey,

        @PrimaryKeyColumn(name = "publication_year", ordinal = 1, type = PrimaryKeyType.CLUSTERED,
                ordering = Ordering.DESCENDING)
        int publicationYear,

        @PrimaryKeyColumn(name = "book_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
        UUID bookId,

        @Column("isbn") String isbn,
        @Column("title") String title,
        @Column("authors") List<String> authors,
        @Column("publisher") String publisher,
        @Column("language") String language,
        @Column("genres") Set<String> genres,
        @Column("added_at") Instant addedAt
) {

    public static BookByTitle of(Book book) {
        return new BookByTitle(BookTextNormalizer.searchKey(book.title()), book.publicationYear(), book.bookId(),
                book.isbn(), book.title(), book.authors(), book.publisher(), book.language(), book.genres(),
                book.addedAt());
    }

    public Book toBook() {
        return new Book(bookId, isbn, title, authors, publisher, publicationYear, language, genres, addedAt);
    }
}
