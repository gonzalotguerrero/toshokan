package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import io.github.gonzalotguerrero.toshokan.catalog.book.Book;
import io.github.gonzalotguerrero.toshokan.catalog.book.BookDetails;
import io.github.gonzalotguerrero.toshokan.inventory.listing.BookListingEntry;
import io.github.gonzalotguerrero.toshokan.inventory.listing.BookSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Creates a book, adds copies to it and checks that {@code GET /books} shows it with the right counts. It's
 * the path the listing depends on, end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BookCopyApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CassandraOperations cassandra;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        cassandra.truncate(Book.class);
        cassandra.truncate(BookCopy.class);
        cassandra.truncate(BookCopyByInventoryNumber.class);
        cassandra.truncate(BookListingEntry.class);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/toshokan").build();
    }

    @Test
    void aBookWithoutCopiesIsNotListed() {
        createBook("Dune");

        assertThat(listBooks()).isEmpty();
    }

    @Test
    void addingTheFirstCopyPutsTheBookInTheListing() {
        BookDetails dune = createBook("Dune");

        BookCopyDetails copy = addBookCopy(dune.bookId(), "LIB-000001");

        assertThat(copy.bookId()).isEqualTo(dune.bookId());
        assertThat(copy.status()).isEqualTo(BookCopyStatus.AVAILABLE);
        assertThat(listBooks()).singleElement().satisfies(book -> {
            assertThat(book.bookId()).isEqualTo(dune.bookId());
            assertThat(book.title()).isEqualTo("Dune");
            assertThat(book.authors()).containsExactly("Frank Herbert");
            assertThat(book.totalCopies()).isEqualTo(1);
            assertThat(book.availableCopies()).isEqualTo(1);
        });
    }

    @Test
    void moreCopiesUpdateTheCounts() {
        BookDetails dune = createBook("Dune");
        BookDetails hobbit = createBook("The Hobbit");

        addBookCopy(dune.bookId(), "LIB-000001");
        addBookCopy(dune.bookId(), "LIB-000002");
        addBookCopy(hobbit.bookId(), "LIB-000003");

        assertThat(listBooks())
                .extracting(BookSummary::title, BookSummary::totalCopies, BookSummary::availableCopies)
                .containsExactly(tuple("Dune", 2, 2), tuple("The Hobbit", 1, 1));
    }

    @Test
    void rejectsAnInventoryNumberAlreadyUsedByAnyBook() {
        BookDetails dune = createBook("Dune");
        BookDetails hobbit = createBook("The Hobbit");
        addBookCopy(dune.bookId(), "LIB-000001");

        client.post().uri("/api/v1/books/{bookId}/copies", hobbit.bookId())
                .contentType(MediaType.APPLICATION_JSON).body("{\"inventoryNumber\": \"LIB-000001\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("Inventory number LIB-000001 is already in use");

        assertThat(listBooks()).extracting(BookSummary::title).containsExactly("Dune");
    }

    @Test
    void answersAnUnknownBookWithNotFound() {
        client.post().uri("/api/v1/books/{bookId}/copies", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).body("{\"inventoryNumber\": \"LIB-000001\"}")
                .exchange()
                .expectStatus().isNotFound();
    }

    private BookDetails createBook(String title) {
        return client.post().uri("/api/v1/books").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": "%s", "authors": ["Frank Herbert"], "publicationYear": 1965,
                         "language": "English", "genres": ["Fiction"]}
                        """.formatted(title))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookDetails.class)
                .returnResult()
                .getResponseBody();
    }

    private BookCopyDetails addBookCopy(UUID bookId, String inventoryNumber) {
        return client.post().uri("/api/v1/books/{bookId}/copies", bookId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"inventoryNumber\": \"" + inventoryNumber + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookCopyDetails.class)
                .returnResult()
                .getResponseBody();
    }

    private List<BookSummary> listBooks() {
        return client.get().uri("/api/v1/books").exchange()
                .expectStatus().isOk()
                .expectBody(BooksPage.class)
                .returnResult()
                .getResponseBody()
                .items();
    }

    private record BooksPage(List<BookSummary> items, String nextPageToken) {
    }
}
