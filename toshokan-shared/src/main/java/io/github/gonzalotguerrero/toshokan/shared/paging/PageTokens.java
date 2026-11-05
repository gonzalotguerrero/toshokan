package io.github.gonzalotguerrero.toshokan.shared.paging;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;

import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * Turns Cassandra paging states into the {@code pageToken} strings clients see, and back again.
 */
public final class PageTokens {

    private PageTokens() {
    }

    /** Returns {@code null} when there's no next page. */
    public static String encode(ByteBuffer pagingState) {
        if (pagingState == null || !pagingState.hasRemaining()) {
            return null;
        }
        ByteBuffer copy = pagingState.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** No token means "start at the first page", so that comes back as {@code null}. */
    public static ByteBuffer decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return ByteBuffer.wrap(Base64.getUrlDecoder().decode(token));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Malformed page token", e);
        }
    }
}
