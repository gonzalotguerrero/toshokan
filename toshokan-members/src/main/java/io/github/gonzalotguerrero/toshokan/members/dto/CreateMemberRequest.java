package io.github.gonzalotguerrero.toshokan.members.dto;

import io.github.gonzalotguerrero.toshokan.members.service.MemberService;
import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/v1/members}. Email and phone are each optional, but {@link MemberService} rejects
 * the request if both are missing.
 */
public record CreateMemberRequest(
        @NotBlank String cardNumber,
        @NotBlank String fullName,
        String email,
        String phone
) {
}
