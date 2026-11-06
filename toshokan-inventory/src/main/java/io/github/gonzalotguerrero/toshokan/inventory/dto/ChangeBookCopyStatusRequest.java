package io.github.gonzalotguerrero.toshokan.inventory.dto;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import jakarta.validation.constraints.NotNull;

/** What the client sends to change a copy's status by hand, e.g. {@code {"status": "DAMAGED"}}. */
public record ChangeBookCopyStatusRequest(@NotNull BookCopyStatus status) {
}
