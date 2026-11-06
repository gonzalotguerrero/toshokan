package io.github.gonzalotguerrero.toshokan.circulation.dto;

import jakarta.validation.constraints.NotBlank;

/** The body of {@code POST /api/v1/renewals}. */
public record RenewalRequest(
        @NotBlank String inventoryNumber
) {
}
