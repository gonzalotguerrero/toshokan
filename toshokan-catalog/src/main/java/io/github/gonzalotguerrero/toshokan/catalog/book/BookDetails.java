package io.github.gonzalotguerrero.toshokan.catalog.book;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the API returns for a book, and what other modules get from {@link BookService}.
 */
public record BookDetails(
        UUID bookId,
        String isbn,
        String title,
        List<String> authors,
        String publisher,
        int publicationYear,
        String language,
        Set<String> genres,
        Instant addedAt
) {

    public static BookDetails from(Book book) {
        return new BookDetails(book.bookId(), book.isbn(), book.title(), book.authors(), book.publisher(),
                book.publicationYear(), book.language(), book.genres(), book.addedAt());
    }
}
