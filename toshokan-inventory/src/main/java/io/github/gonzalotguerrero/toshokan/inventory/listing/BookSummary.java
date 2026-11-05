package io.github.gonzalotguerrero.toshokan.inventory.listing;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record BookSummary(
        UUID bookId,
        String isbn,
        String title,
        List<String> authors,
        String publisher,
        Integer publicationYear,
        String language,
        Set<String> genres,
        int totalCopies,
        int availableCopies
) {

    static BookSummary from(BookListingEntry entry) {
        return new BookSummary(entry.bookId(), entry.isbn(), entry.title(),
                entry.authors() == null ? List.of() : entry.authors(),
                entry.publisher(), entry.publicationYear(), entry.language(),
                entry.genres() == null ? Set.of() : entry.genres(),
                entry.totalCopies(), entry.availableCopies());
    }
}
