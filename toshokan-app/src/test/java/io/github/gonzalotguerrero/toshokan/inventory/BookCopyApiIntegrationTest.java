package io.github.gonzalotguerrero.toshokan.inventory;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookSummary;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyByInventoryNumber;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookHoldings;
import io.github.gonzalotguerrero.toshokan.inventory.repository.InventoryNumberClaims;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * Adds, changes and withdraws copies through the API and checks that {@code GET /books} keeps showing the
 * right counts. It's the path the holdings depend on, end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BookCopyApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CassandraOperations cassandra;

    @Autowired
    private InventoryNumberClaims inventoryNumberClaims;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        cassandra.truncate(Book.class);
        cassandra.truncate(BookCopy.class);
        cassandra.truncate(BookCopyByInventoryNumber.class);
        cassandra.truncate(BookHoldings.class);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/toshokan").build();
    }

    @Test
    void aBookWithoutCopiesIsNotListed() {
        createBook("Dune");

        assertThat(listBooks()).isEmpty();
    }

    @Test
    void addingTheFirstCopyPutsTheBookInTheHoldings() {
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

    @Test
    void listsTheCopiesOfABook() {
        BookDetails dune = createBook("Dune");
        BookCopyDetails second = addBookCopy(dune.bookId(), "LIB-000002");
        BookCopyDetails first = addBookCopy(dune.bookId(), "LIB-000001");

        assertThat(listBookCopies(dune.bookId())).containsExactly(first, second);
    }

    @Test
    void aDamagedCopyStillCountsButIsNotAvailable() {
        BookDetails dune = createBook("Dune");
        BookCopyDetails copy = addBookCopy(dune.bookId(), "LIB-000001");
        addBookCopy(dune.bookId(), "LIB-000002");

        BookCopyDetails damaged = changeStatus(copy, "DAMAGED")
                .expectStatus().isOk()
                .expectBody(BookCopyDetails.class).returnResult().getResponseBody();

        assertThat(damaged.status()).isEqualTo(BookCopyStatus.DAMAGED);
        assertThat(listBooks()).singleElement().satisfies(book -> {
            assertThat(book.totalCopies()).isEqualTo(2);
            assertThat(book.availableCopies()).isEqualTo(1);
        });
    }

    @Test
    void aCopyCannotBeMarkedAsLentByHand() {
        BookDetails dune = createBook("Dune");
        BookCopyDetails copy = addBookCopy(dune.bookId(), "LIB-000001");

        changeStatus(copy, "LENT").expectStatus().isEqualTo(409);

        assertThat(listBookCopies(dune.bookId())).singleElement()
                .extracting(BookCopyDetails::status).isEqualTo(BookCopyStatus.AVAILABLE);
    }

    @Test
    void deletingACopyWithdrawsItButKeepsItsRecord() {
        BookDetails dune = createBook("Dune");
        BookCopyDetails copy = addBookCopy(dune.bookId(), "LIB-000001");
        addBookCopy(dune.bookId(), "LIB-000002");

        withdraw(copy).expectStatus().isNoContent();

        assertThat(listBookCopies(dune.bookId()))
                .extracting(BookCopyDetails::inventoryNumber, BookCopyDetails::status)
                .containsExactly(tuple("LIB-000001", BookCopyStatus.WITHDRAWN),
                        tuple("LIB-000002", BookCopyStatus.AVAILABLE));
        assertThat(listBooks()).singleElement().satisfies(book -> {
            assertThat(book.totalCopies()).isEqualTo(1);
            assertThat(book.availableCopies()).isEqualTo(1);
        });
    }

    @Test
    void withdrawingTheLastCopyTakesTheBookOutOfTheHoldings() {
        BookDetails dune = createBook("Dune");
        BookCopyDetails copy = addBookCopy(dune.bookId(), "LIB-000001");

        withdraw(copy).expectStatus().isNoContent();

        assertThat(listBooks()).isEmpty();
    }

    @Test
    void aWithdrawnCopyCanBePutBackOnTheShelf() {
        BookDetails dune = createBook("Dune");
        BookCopyDetails copy = addBookCopy(dune.bookId(), "LIB-000001");
        withdraw(copy).expectStatus().isNoContent();

        changeStatus(copy, "AVAILABLE").expectStatus().isOk();

        assertThat(listBooks()).singleElement().satisfies(book -> {
            assertThat(book.totalCopies()).isEqualTo(1);
            assertThat(book.availableCopies()).isEqualTo(1);
        });
    }

    @Test
    void answersAnUnknownCopyWithNotFound() {
        BookDetails dune = createBook("Dune");

        client.delete().uri("/api/v1/books/{bookId}/copies/{bookCopyId}", dune.bookId(), UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void aNewCopyTakesOverAnOldNumberClaimWhoseCopyNeverGotSaved() {
        BookDetails dune = createBook("Dune");
        // What adding a copy leaves behind if saving the copy failed in a way that might still have worked.
        // Written with an old timestamp on purpose, so it looks like it's been there for an hour.
        long anHourAgo = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now().minus(Duration.ofHours(1)));
        cassandra.getCqlOperations().execute("INSERT INTO book_copies_by_inventory_number"
                        + " (inventory_number, book_id, book_copy_id) VALUES (?, ?, ?) USING TIMESTAMP " + anHourAgo,
                "LIB-000001", dune.bookId(), UUID.randomUUID());

        BookCopyDetails copy = addBookCopy(dune.bookId(), "LIB-000001");

        assertThat(listBookCopies(dune.bookId())).extracting(BookCopyDetails::bookCopyId)
                .containsExactly(copy.bookCopyId());
    }

    @Test
    void aFreshNumberClaimIsLeftAloneEvenIfItsCopyIsNotThereYet() {
        BookDetails dune = createBook("Dune");
        // Looks just like a copy being added that has claimed the number and is about to be saved
        assertThat(inventoryNumberClaims.claim("LIB-000001", dune.bookId(), UUID.randomUUID())).isTrue();

        client.post().uri("/api/v1/books/{bookId}/copies", dune.bookId()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"inventoryNumber\": \"LIB-000001\"}")
                .exchange()
                .expectStatus().isEqualTo(409);
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

    private List<BookCopyDetails> listBookCopies(UUID bookId) {
        return client.get().uri("/api/v1/books/{bookId}/copies", bookId).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<BookCopyDetails>>() {
                })
                .returnResult()
                .getResponseBody();
    }

    private RestTestClient.ResponseSpec changeStatus(BookCopyDetails copy, String status) {
        return client.patch().uri("/api/v1/books/{bookId}/copies/{bookCopyId}", copy.bookId(), copy.bookCopyId())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\": \"" + status + "\"}")
                .exchange();
    }

    private RestTestClient.ResponseSpec withdraw(BookCopyDetails copy) {
        return client.delete().uri("/api/v1/books/{bookId}/copies/{bookCopyId}", copy.bookId(), copy.bookCopyId())
                .exchange();
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
