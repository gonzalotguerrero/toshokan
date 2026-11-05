package io.github.gonzalotguerrero.toshokan.inventory.listing;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole app and calls it over real HTTP, backed by a real Cassandra. Slower than the unit
 * tests, but it's the only place mistakes in the /toshokan base path, the table mapping, title ordering
 * or paging would show up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BookListingApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private BookListingRepository repository;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/toshokan").build();
    }

    @Test
    void listsEveryOwnedTitleInTitleOrderByDefault() {
        save("the Hobbit", 2, 0);
        save("Dune", 3, 1);
        save("anathem", 1, 1);

        BooksPage page = getBooks("/api/v1/books");

        assertThat(page.items()).extracting(BookSummary::title).containsExactly("anathem", "Dune", "the Hobbit");
        assertThat(page.nextPageToken()).isNull();
    }

    @Test
    void listsOnlyTitlesWithACopyOnTheShelfWhenAvailableIsTrue() {
        save("The Hobbit", 2, 0);
        save("Dune", 3, 1);

        BooksPage page = getBooks("/api/v1/books?available=true");

        assertThat(page.items()).extracting(BookSummary::title).containsExactly("Dune");
    }

    @Test
    void returnsBookDetailsAndCopyCounts() {
        BookListingEntry dune = save("Dune", 3, 1);

        BookSummary book = getBooks("/api/v1/books").items().getFirst();

        assertThat(book.bookId()).isEqualTo(dune.bookId());
        assertThat(book.isbn()).isEqualTo(dune.isbn());
        assertThat(book.authors()).containsExactly("Some Author");
        assertThat(book.publisher()).isEqualTo("Some Publisher");
        assertThat(book.publicationYear()).isEqualTo(2001);
        assertThat(book.language()).isEqualTo("en");
        assertThat(book.genres()).containsExactly("fiction");
        assertThat(book.totalCopies()).isEqualTo(3);
        assertThat(book.availableCopies()).isEqualTo(1);
    }

    @Test
    void pagesThroughAllTitlesUsingTheNextPageToken() {
        for (char letter = 'A'; letter <= 'E'; letter++) {
            save(letter + " title", 1, 1);
        }

        List<String> titles = new ArrayList<>();
        String token = null;
        int requests = 0;
        do {
            BooksPage page = getBooks("/api/v1/books?pageSize=2" + (token == null ? "" : "&pageToken=" + token));
            page.items().forEach(book -> titles.add(book.title()));
            token = page.nextPageToken();
            requests++;
        } while (token != null && requests < 10);

        assertThat(titles).containsExactly("A title", "B title", "C title", "D title", "E title");
    }

    @Test
    void rejectsAnInvalidPageToken() {
        save("Dune", 3, 1);

        client.get().uri("/api/v1/books?pageToken=AAAA").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("Invalid page token");
    }

    @Test
    void rejectsAnOutOfRangePageSize() {
        client.get().uri("/api/v1/books?pageSize=0").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("pageSize must be between 1 and 100");
    }

    @Test
    void isNotServedOutsideTheToshokanBasePath() {
        RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .get().uri("/api/v1/books").exchange()
                .expectStatus().isNotFound();
    }

    private BookListingEntry save(String title, int totalCopies, int availableCopies) {
        return repository.save(BookListingEntry.of(UUID.randomUUID(), "isbn-" + title, title, List.of("Some Author"),
                "Some Publisher", 2001, "en", Set.of("fiction"), totalCopies, availableCopies));
    }

    private BooksPage getBooks(String uri) {
        return client.get().uri(uri).exchange()
                .expectStatus().isOk()
                .expectBody(BooksPage.class)
                .returnResult()
                .getResponseBody();
    }

    private record BooksPage(List<BookSummary> items, String nextPageToken) {
    }
}
