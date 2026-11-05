package io.github.gonzalotguerrero.toshokan.inventory.listing;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A title the library has at least one copy of, lent or not.
 *
 * <p>Every row has the same {@code listing_partition} ({@link #LISTING_PARTITION}), so the whole listing
 * sits in one Cassandra partition, sorted by title regardless of case. One library's catalog fits in a
 * partition easily. If it ever doesn't, this is the column to split on.
 *
 * <p>Book details are copied over from the catalog. That's safe because a book can't be edited once it's
 * created. The copy counts get recalculated whenever one of the book's copies changes.
 */
@Table("book_listing")
public record BookListingEntry(

        @PrimaryKeyColumn(name = "listing_partition", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        int listingPartition,

        @PrimaryKeyColumn(name = "sort_title", ordinal = 1, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
        String sortTitle,

        @PrimaryKeyColumn(name = "book_id", ordinal = 2, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.ASCENDING)
        UUID bookId,

        @Column("isbn") String isbn,
        @Column("title") String title,
        @Column("authors") List<String> authors,
        @Column("publisher") String publisher,
        @Column("publication_year") Integer publicationYear,
        @Column("language") String language,
        @Column("genres") Set<String> genres,
        @Column("total_copies") int totalCopies,
        @Column("available_copies") int availableCopies
) {

    public static final int LISTING_PARTITION = 0;

    public static String sortKeyFor(String title) {
        return title.strip().toLowerCase(Locale.ROOT);
    }

    public static BookListingEntry of(UUID bookId, String isbn, String title, List<String> authors, String publisher,
                                      Integer publicationYear, String language, Set<String> genres,
                                      int totalCopies, int availableCopies) {
        return new BookListingEntry(LISTING_PARTITION, sortKeyFor(title), bookId, isbn, title, authors, publisher,
                publicationYear, language, genres, totalCopies, availableCopies);
    }
}
