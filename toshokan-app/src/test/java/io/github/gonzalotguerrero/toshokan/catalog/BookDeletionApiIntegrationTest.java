package io.github.gonzalotguerrero.toshokan.catalog;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByAuthor;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByIsbn;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByTitle;
import io.github.gonzalotguerrero.toshokan.catalog.repository.IsbnClaims;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookSummary;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyByInventoryNumber;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookHoldings;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookLock;
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
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deletes books through the API against a real Cassandra: when it's allowed, what's left behind, and how the ISBN
 * becomes free again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BookDeletionApiIntegrationTest {

    private static final String ISBN = "9780441013593";

    @LocalServerPort
    private int port;

    @Autowired
    private CassandraOperations cassandra;

    @Autowired
    private IsbnClaims isbnClaims;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        for (Class<?> table : List.of(Book.class, BookByIsbn.class, BookByTitle.class, BookByAuthor.class,
                BookCopy.class, BookCopyByInventoryNumber.class, BookHoldings.class, BookLock.class)) {
            cassandra.truncate(table);
        }
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/toshokan").build();
    }

    @Test
    void aBookWithoutCopiesIsGoneEverywhereAndItsIsbnIsFree() {
        BookDetails dune = createBook(ISBN).expectStatus().isCreated()
                .expectBody(BookDetails.class).returnResult().getResponseBody();

        deleteBook(dune.bookId()).expectStatus().isNoContent();

        client.get().uri("/api/v1/books/{bookId}", dune.bookId()).exchange().expectStatus().isNotFound();
        assertThat(search("title", "Dune")).isEmpty();
        assertThat(search("author", "Frank Herbert")).isEmpty();
        assertThat(search("isbn", ISBN)).isEmpty();
        createBook(ISBN).expectStatus().isCreated();
    }

    @Test
    void aBookWithACopyOnTheShelfCannotBeDeleted() {
        BookDetails dune = createBook(ISBN).expectStatus().isCreated()
                .expectBody(BookDetails.class).returnResult().getResponseBody();
        addCopy(dune.bookId(), "LIB-000001");

        deleteBook(dune.bookId()).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("Book " + dune.bookId() + " still has 1 copy that "
                        + "isn't withdrawn. It has to be withdrawn before the book can be deleted");

        client.get().uri("/api/v1/books/{bookId}", dune.bookId()).exchange().expectStatus().isOk();
    }

    @Test
    void onceEveryCopyIsWithdrawnTheBookCanGoButTheCopiesStayOnRecord() {
        BookDetails dune = createBook(ISBN).expectStatus().isCreated()
                .expectBody(BookDetails.class).returnResult().getResponseBody();
        BookCopyDetails copy = addCopy(dune.bookId(), "LIB-000001");
        client.delete().uri("/api/v1/books/{bookId}/copies/{bookCopyId}", dune.bookId(), copy.bookCopyId())
                .exchange().expectStatus().isNoContent();

        deleteBook(dune.bookId()).expectStatus().isNoContent();

        assertThat(holdings()).isEmpty();
        // The copy's record and its loan history are still there, and its label is never handed out again
        client.get().uri("/api/v1/books/{bookId}/copies/{bookCopyId}/loan-history", dune.bookId(),
                copy.bookCopyId()).exchange().expectStatus().isOk();
        BookDetails other = createBook(null).expectStatus().isCreated()
                .expectBody(BookDetails.class).returnResult().getResponseBody();
        client.post().uri("/api/v1/books/{bookId}/copies", other.bookId()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"inventoryNumber\": \"LIB-000001\"}").exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void aNewBookTakesOverAnOldClaimWhoseBookNeverGotSaved() {
        // What a create leaves behind if saving the book failed and the ISBN couldn't be freed either. Written
        // with an old timestamp on purpose, so it looks like it's been there for an hour.
        long anHourAgo = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now().minus(Duration.ofHours(1)));
        cassandra.getCqlOperations().execute(
                "INSERT INTO books_by_isbn (isbn, book_id) VALUES (?, ?) USING TIMESTAMP " + anHourAgo,
                ISBN, UUID.randomUUID());

        BookDetails dune = createBook(ISBN).expectStatus().isCreated()
                .expectBody(BookDetails.class).returnResult().getResponseBody();

        assertThat(search("isbn", ISBN)).containsExactly(dune);
    }

    @Test
    void aFreshClaimIsLeftAloneEvenIfItsBookIsNotThereYet() {
        // Looks just like a create that has claimed the ISBN and is about to save its book
        assertThat(isbnClaims.claim(ISBN, UUID.randomUUID())).isTrue();

        createBook(ISBN).expectStatus().isEqualTo(409);
    }

    @Test
    void deletingAnUnknownBookIsNotFound() {
        deleteBook(UUID.randomUUID()).expectStatus().isNotFound();
    }

    private RestTestClient.ResponseSpec createBook(String isbn) {
        return client.post().uri("/api/v1/books").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"isbn": %s, "title": "Dune", "authors": ["Frank Herbert"], "publicationYear": 1965,
                         "language": "English", "genres": ["Science fiction"]}
                        """.formatted(isbn == null ? "null" : "\"" + isbn + "\""))
                .exchange();
    }

    private RestTestClient.ResponseSpec deleteBook(UUID bookId) {
        return client.delete().uri("/api/v1/books/{bookId}", bookId).exchange();
    }

    private BookCopyDetails addCopy(UUID bookId, String inventoryNumber) {
        return client.post().uri("/api/v1/books/{bookId}/copies", bookId).contentType(MediaType.APPLICATION_JSON)
                .body("{\"inventoryNumber\": \"" + inventoryNumber + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookCopyDetails.class).returnResult().getResponseBody();
    }

    private List<BookDetails> search(String by, String value) {
        return client.get().uri(builder -> builder.path("/api/v1/books/search").queryParam(by, value).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<BookDetails>>() {
                })
                .returnResult().getResponseBody().items();
    }

    private List<BookSummary> holdings() {
        return client.get().uri("/api/v1/books").exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<BookSummary>>() {
                })
                .returnResult().getResponseBody().items();
    }
}
