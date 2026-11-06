package io.github.gonzalotguerrero.toshokan.catalog.service;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Cleans up the free-text fields before a book is saved. "english", "ENGLISH" and " English " should all be
 * stored the same way.
 */
public final class BookTextNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ISBN_SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern VALID_ISBN = Pattern.compile("\\d{9}[\\dX]|\\d{13}");

    private BookTextNormalizer() {
    }

    /** Upper-cases the first letter and lower-cases the rest, so "science FICTION" becomes "Science fiction". */
    public static String capitalize(String value) {
        String text = WHITESPACE.matcher(value.strip()).replaceAll(" ");
        if (text.isEmpty()) {
            return text;
        }
        int firstLetterEnd = text.offsetByCodePoints(0, 1);
        return text.substring(0, firstLetterEnd).toUpperCase(Locale.ROOT)
                + text.substring(firstLetterEnd).toLowerCase(Locale.ROOT);
    }

    /**
     * Capitalizes each genre, which also merges the ones that only differed in case. They're sorted because
     * Cassandra stores sets sorted anyway, and this way the create response already matches what later reads
     * return.
     */
    public static Set<String> normalizeGenres(Collection<String> genres) {
        Set<String> normalized = new TreeSet<>();
        for (String genre : genres) {
            normalized.add(capitalize(genre));
        }
        return normalized;
    }

    /**
     * Removes hyphens and spaces, so "978-0-441-01359-3" and "9780441013593" are treated as the same ISBN.
     * A missing ISBN is allowed and comes back as {@code null}.
     */
    public static String normalizeIsbn(String isbn) {
        if (isbn == null || isbn.isBlank()) {
            return null;
        }
        String normalized = ISBN_SEPARATORS.matcher(isbn).replaceAll("").toUpperCase(Locale.ROOT);
        if (!VALID_ISBN.matcher(normalized).matches()) {
            throw new InvalidRequestException("isbn must have 10 or 13 digits (an ISBN-10 may end in X)");
        }
        return normalized;
    }

    /**
     * The form a title or author name is matched by when searching. It's still a whole-value match, but case,
     * extra spaces and character width don't count, so "dune", "  DUNE " and full-width "ＤＵＮＥ" all match "Dune".
     * NFKC is what turns full-width letters (and other look-alike forms) into the ordinary ones.
     */
    public static String searchKey(String value) {
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(text.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** Trims optional text. A blank value counts as not given. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
