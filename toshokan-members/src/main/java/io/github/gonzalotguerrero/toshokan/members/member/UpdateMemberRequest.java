package io.github.gonzalotguerrero.toshokan.members.member;

/**
 * The body of {@code PATCH /api/v1/members/{memberId}}. Every field is optional, and a field that's left out (or
 * sent as null) stays as it was. An empty string clears email or phone, as long as the member keeps at least one
 * of the two. The card number can't be changed.
 */
public record UpdateMemberRequest(
        String fullName,
        String email,
        String phone,
        MemberStatus status
) {
}
