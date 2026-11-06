package io.github.gonzalotguerrero.toshokan.circulation.dto;

import jakarta.validation.constraints.NotBlank;

/** The body of {@code POST /api/v1/loans}: the number on the book's label and the one on the member's card. */
public record LendRequest(
        @NotBlank String inventoryNumber,
        @NotBlank String cardNumber
) {
}
