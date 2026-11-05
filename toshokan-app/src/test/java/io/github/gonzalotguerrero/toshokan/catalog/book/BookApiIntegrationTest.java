package io.github.gonzalotguerrero.toshokan.catalog.book;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creates and fetches books through the running app, backed by a real Cassandra. Normalization and ISBN
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
