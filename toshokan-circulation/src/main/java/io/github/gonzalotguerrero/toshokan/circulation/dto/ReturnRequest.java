package io.github.gonzalotguerrero.toshokan.circulation.dto;

import jakarta.validation.constraints.NotBlank;

/** The body of {@code POST /api/v1/returns}. The book is enough, since it can only be lent to one member. */
public record ReturnRequest(
        @NotBlank String inventoryNumber
) {
}
