package io.github.gonzalotguerrero.toshokan.catalog;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByAuthor;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByIsbn;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByTitle;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creates, fetches and searches books through the running app, backed by a real Cassandra. Normalization and ISBN
 * uniqueness are tested here too, since you only know they work once the data has been saved and read back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BookApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CassandraOperations cassandra;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        cassandra.truncate(Book.class);
        cassandra.truncate(BookByIsbn.class);
        cassandra.truncate(BookByTitle.class);
        cassandra.truncate(BookByAuthor.class);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/toshokan").build();
    }

    @Test
    void createsABookAndServesItFromItsLocation() {
        EntityExchangeResult<BookDetails> result = client.post().uri("/api/v1/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"isbn": "978-0-441-01359-3", "title": " Dune ", "authors": ["Frank Herbert"],
                         "publisher": "Ace", "publicationYear": 1965, "language": "eNGLISH",
                         "genres": ["science FICTION"]}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookDetails.class)
                .returnResult();
        BookDetails created = result.getResponseBody();

        assertThat(created.isbn()).isEqualTo("9780441013593");
        assertThat(created.title()).isEqualTo("Dune");
        assertThat(created.language()).isEqualTo("English");
        assertThat(created.genres()).containsExactly("Science fiction");
        assertThat(result.getResponseHeaders().getLocation()).hasToString(
                "http://localhost:" + port + "/toshokan/api/v1/books/" + created.bookId());

        BookDetails fetched = client.get().uri("/api/v1/books/{bookId}", created.bookId()).exchange()
                .expectStatus().isOk()
                .expectBody(BookDetails.class)
                .returnResult()
                .getResponseBody();
        assertThat(fetched).isEqualTo(created);
    }

    @Test
    void storesGenresThatOnlyDifferInCaseAsOne() {
        BookDetails created = createBook("""
                {"title": "It", "authors": ["Stephen King"], "publicationYear": 1986, "language": "English",
                 "genres": ["Horror", "horror", "hOrRor"]}
                """);

        BookDetails fetched = client.get().uri("/api/v1/books/{bookId}", created.bookId()).exchange()
                .expectBody(BookDetails.class).returnResult().getResponseBody();
        assertThat(fetched.genres()).containsExactly("Horror");
    }

    @Test
    void rejectsAnIsbnAnotherBookHasEvenWhenWrittenDifferently() {
        createBook(bookWithIsbn("9780441013593"));

        client.post().uri("/api/v1/books").contentType(MediaType.APPLICATION_JSON)
                .body(bookWithIsbn("978-0-441-01359-3"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("A book with ISBN 9780441013593 already exists");
    }

    @Test
    void allowsManyBooksWithoutIsbn() {
        BookDetails first = createBook(bookWithIsbn(null));
        BookDetails second = createBook(bookWithIsbn(null));

        assertThat(first.bookId()).isNotEqualTo(second.bookId());
    }

    @Test
    void rejectsAMalformedIsbn() {
        client.post().uri("/api/v1/books").contentType(MediaType.APPLICATION_JSON)
                .body(bookWithIsbn("12345"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("isbn must have 10 or 13 digits (an ISBN-10 may end in X)");
    }

    @Test
    void listsTheMissingFields() {
        client.post().uri("/api/v1/books").contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": \"Dune\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.authors").exists()
                .jsonPath("$.errors.publicationYear").exists()
                .jsonPath("$.errors.language").exists()
                .jsonPath("$.errors.genres").exists();
    }

    @Test
    void answersAnUnknownBookWithNotFound() {
        client.get().uri("/api/v1/books/{bookId}", UUID.randomUUID()).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void findsAnAuthorsBooksSortedByTitleHoweverTheNameIsTyped() {
        createBook(book("Dune", 1965, "Frank Herbert"));
        createBook(book("Children of Dune", 1976, "Frank Herbert"));
        createBook(book("Good Omens", 1990, "Terry Pratchett", "Neil Gaiman"));

        assertThat(search(Map.of("author", "  frank   HERBERT ")).items()).extracting(BookDetails::title)
                .containsExactly("Children of Dune", "Dune");
        // A book with two authors can be found by either of them
        assertThat(search(Map.of("author", "Neil Gaiman")).items()).extracting(BookDetails::title)
                .containsExactly("Good Omens");
        assertThat(search(Map.of("author", "Herbert")).items()).isEmpty();
    }

    @Test
    void findsEveryEditionOfATitleNewestFirstButOnlyWholeTitles() {
        createBook(book("Dune", 1965, "Frank Herbert"));
        createBook(book("DUNE", 2005, "Frank Herbert"));
        createBook(book("Dune Messiah", 1969, "Frank Herbert"));

        // Typed in full-width letters
        assertThat(search(Map.of("title", "Ｄｕｎｅ")).items())
                .extracting(BookDetails::publicationYear).containsExactly(2005, 1965);
    }

    @Test
    void findsABookByIsbn() {
        BookDetails dune = createBook(bookWithIsbn("9780441013593"));

        assertThat(search(Map.of("isbn", "978-0-441-01359-3")).items()).containsExactly(dune);
        assertThat(search(Map.of("isbn", "9780000000002")).items()).isEmpty();
    }

    @Test
    void searchResultsComeInPages() {
        for (String title : List.of("Dune", "Children of Dune", "Dune Messiah")) {
            createBook(book(title, 1970, "Frank Herbert"));
        }

        PageResponse<BookDetails> first = search(Map.of("author", "Frank Herbert", "pageSize", "2"));
        PageResponse<BookDetails> second = search(Map.of("author", "Frank Herbert", "pageSize", "2",
                "pageToken", first.nextPageToken()));

        assertThat(first.items()).extracting(BookDetails::title).containsExactly("Children of Dune", "Dune");
        assertThat(second.items()).extracting(BookDetails::title).containsExactly("Dune Messiah");
    }

    @Test
    void needsExactlyOneThingToSearchBy() {
        client.get().uri("/api/v1/books/search").exchange().expectStatus().isBadRequest();
        client.get().uri(builder -> builder.path("/api/v1/books/search").queryParam("title", "Dune")
                        .queryParam("author", "Frank Herbert").build())
                .exchange().expectStatus().isBadRequest();
    }

    private PageResponse<BookDetails> search(Map<String, String> params) {
        return client.get()
                .uri(builder -> {
                    builder.path("/api/v1/books/search");
                    params.forEach(builder::queryParam);
                    return builder.build();
                })
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<BookDetails>>() {
                })
                .returnResult()
                .getResponseBody();
    }

    private static String book(String title, int year, String... authors) {
        return """
                {"title": "%s", "authors": ["%s"], "publicationYear": %d, "language": "English",
                 "genres": ["Science fiction"]}
                """.formatted(title, String.join("\", \"", authors), year);
    }

    private BookDetails createBook(String json) {
        return client.post().uri("/api/v1/books").contentType(MediaType.APPLICATION_JSON).body(json)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookDetails.class)
                .returnResult()
                .getResponseBody();
    }

    private static String bookWithIsbn(String isbn) {
        return """
                {"isbn": %s, "title": "Dune", "authors": ["Frank Herbert"], "publicationYear": 1965,
                 "language": "English", "genres": ["Science fiction"]}
                """.formatted(isbn == null ? "null" : "\"" + isbn + "\"");
    }
}
