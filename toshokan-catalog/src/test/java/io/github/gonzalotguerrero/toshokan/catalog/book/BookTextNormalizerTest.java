package io.github.gonzalotguerrero.toshokan.catalog.book;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookTextNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "english, English",
            "ENGLISH, English",
            "eNgLiSh, English",
            "'  english  ', English",
            "science FICTION, Science fiction",
            "'science    fiction', Science fiction",
            "español, Español",
            "en, En",
            "x, X"
    })
    void capitalizesFirstLetterOnly(String input, String expected) {
        assertThat(BookTextNormalizer.capitalize(input)).isEqualTo(expected);
    }

    @Test
    void mergesGenresThatOnlyDifferInCase() {
        assertThat(BookTextNormalizer.normalizeGenres(List.of("Horror", "horror", "hOrRor")))
                .containsExactly("Horror");
    }

    @Test
    void keepsDifferentGenresSorted() {
        assertThat(BookTextNormalizer.normalizeGenres(List.of("thriller", "HORROR", "Thriller ")))
                .containsExactly("Horror", "Thriller");
    }

    @ParameterizedTest
    @CsvSource({
            "9780441013593, 9780441013593",
            "978-0-441-01359-3, 9780441013593",
            "'978 0 441 01359 3', 9780441013593",
            "0-8044-2957-x, 080442957X"
    })
    void stripsSeparatorsFromIsbn(String input, String expected) {
        assertThat(BookTextNormalizer.normalizeIsbn(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void treatsMissingIsbnAsNone(String isbn) {
        assertThat(BookTextNormalizer.normalizeIsbn(isbn)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "97804410135930", "978044101359X", "ISBN 9780441013593", "08044X2957"})
    void rejectsMalformedIsbn(String isbn) {
        assertThatThrownBy(() -> BookTextNormalizer.normalizeIsbn(isbn))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("10 or 13 digits");
    }

    @Test
    void turnsBlankOptionalTextIntoNull() {
        assertThat(BookTextNormalizer.blankToNull(null)).isNull();
        assertThat(BookTextNormalizer.blankToNull("  ")).isNull();
        assertThat(BookTextNormalizer.blankToNull(" Ace ")).isEqualTo("Ace");
    }
}
