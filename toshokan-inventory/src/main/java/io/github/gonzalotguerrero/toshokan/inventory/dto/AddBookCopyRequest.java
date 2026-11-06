package io.github.gonzalotguerrero.toshokan.inventory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/v1/books/{bookId}/copies}. The library assigns the inventory number, so the
 * client sends it.
 */
public record AddBookCopyRequest(@NotBlank String inventoryNumber) {
}
