package io.github.gonzalotguerrero.toshokan.catalog.book;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The body of {@code POST /api/v1/books}. Everything is required except ISBN and publisher. Values come in
 * exactly as the client typed them, and {@link BookService} tidies them up before saving.
 */
public record CreateBookRequest(
        String isbn,
        @NotBlank String title,
        @NotEmpty List<@NotBlank String> authors,
        String publisher,
        @NotNull Integer publicationYear,
        @NotBlank String language,
        @NotEmpty List<@NotBlank String> genres
) {
}
