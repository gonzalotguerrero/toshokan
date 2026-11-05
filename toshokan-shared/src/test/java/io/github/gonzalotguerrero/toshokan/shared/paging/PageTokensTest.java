package io.github.gonzalotguerrero.toshokan.shared.paging;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageTokensTest {

    @Test
    void roundTripsAPagingState() {
        ByteBuffer state = ByteBuffer.wrap(new byte[]{0, 1, 2, (byte) 0xFE, (byte) 0xFF});

        String token = PageTokens.encode(state);

        assertThat(token).doesNotContain("+", "/", "=");
        assertThat(PageTokens.decode(token)).isEqualTo(state);
    }

    @Test
    void encodingDoesNotConsumeTheBuffer() {
        ByteBuffer state = ByteBuffer.wrap(new byte[]{1, 2, 3});

        PageTokens.encode(state);

        assertThat(state.remaining()).isEqualTo(3);
    }

    @Test
    void encodesMissingOrEmptyStateAsNoToken() {
        assertThat(PageTokens.encode(null)).isNull();
        assertThat(PageTokens.encode(ByteBuffer.allocate(0))).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void decodesMissingTokenAsFirstPage(String token) {
        assertThat(PageTokens.decode(token)).isNull();
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> PageTokens.decode("not base64!"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Malformed page token");
    }
}
