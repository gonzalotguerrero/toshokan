package io.github.gonzalotguerrero.toshokan.catalog.model;

import io.github.gonzalotguerrero.toshokan.catalog.service.BookTextNormalizer;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Books grouped by author, for searching by author. A book with several authors has one row per author. Each
 * author's books share a partition, sorted by title.
 *
 * <p>Like {@link BookByTitle}, the row holds the whole book, which is safe because books never change and the rows
 * are written in the same logged batch as the book.
 */
@Table("books_by_author")
public record BookByAuthor(

        @PrimaryKeyColumn(name = "author_key", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        String authorKey,

        @PrimaryKeyColumn(name = "title_key", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
        String titleKey,

        @PrimaryKeyColumn(name = "book_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED)
        UUID bookId,

        @Column("isbn") String isbn,
        @Column("title") String title,
        @Column("authors") List<String> authors,
        @Column("publisher") String publisher,
        @Column("publication_year") int publicationYear,
        @Column("language") String language,
        @Column("genres") Set<String> genres,
        @Column("added_at") Instant addedAt
) {

    /**
     * One row per author. Two names that only differ in case or spacing would land on the same row anyway, so
     * they're merged here.
     */
    public static List<BookByAuthor> of(Book book) {
        Set<String> authorKeys = new LinkedHashSet<>();
        for (String author : book.authors()) {
            authorKeys.add(BookTextNormalizer.searchKey(author));
        }
        String titleKey = BookTextNormalizer.searchKey(book.title());
        return authorKeys.stream()
                .map(authorKey -> new BookByAuthor(authorKey, titleKey, book.bookId(), book.isbn(), book.title(),
                        book.authors(), book.publisher(), book.publicationYear(), book.language(), book.genres(),
                        book.addedAt()))
                .toList();
    }

    public Book toBook() {
        return new Book(bookId, isbn, title, authors, publisher, publicationYear, language, genres, addedAt);
    }
}
