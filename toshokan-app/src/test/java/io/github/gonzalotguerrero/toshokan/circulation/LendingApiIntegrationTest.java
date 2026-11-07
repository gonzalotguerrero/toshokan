package io.github.gonzalotguerrero.toshokan.circulation;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanDetails;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByCopy;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByMember;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookSummary;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyByInventoryNumber;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookHoldings;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberByCardNumber;
import io.github.gonzalotguerrero.toshokan.members.model.MemberDirectoryEntry;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lends, returns and renews copies through the API against a real Cassandra, and checks that the copies, the
 * holdings and the member's loans all tell the same story afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LendingApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CassandraOperations cassandra;

    @Autowired
    private Clock clock;

    private RestTestClient client;

    private BookDetails dune;

    @BeforeEach
    void setUp() {
        for (Class<?> table : List.of(Book.class, BookCopy.class, BookCopyByInventoryNumber.class, BookHoldings.class,
                Member.class, MemberByCardNumber.class, MemberDirectoryEntry.class,
                LoanByCopy.class, LoanByMember.class)) {
            cassandra.truncate(table);
        }
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/toshokan").build();
        dune = createBook();
    }

    @Test
    void lendingShowsWhoHasTheCopyAndUntilWhen() {
        addCopy("LIB-000001");
        MemberDetails ana = createMember("CARD-0001");

        LoanDetails loan = lend("LIB-000001", "CARD-0001").expectStatus().isCreated()
                .expectBody(LoanDetails.class).returnResult().getResponseBody();

        assertThat(loan.memberId()).isEqualTo(ana.memberId());
        assertThat(loan.dueDate()).isEqualTo(LocalDate.now(clock).plusDays(14));
        assertThat(loan.renewals()).isZero();
        assertThat(loan.overdue()).isFalse();
        assertThat(listCopies()).singleElement().satisfies(copy -> {
            assertThat(copy.status()).isEqualTo(BookCopyStatus.LENT);
            assertThat(copy.loan().memberId()).isEqualTo(ana.memberId());
            assertThat(copy.loan().dueDate()).isEqualTo(loan.dueDate());
        });
        assertThat(memberLoans(ana.memberId())).containsExactly(loan);
        assertThat(holdings().availableCopies()).isZero();
    }

    @Test
    void returningPutsTheCopyBackAndClearsTheLoan() {
        addCopy("LIB-000001");
        MemberDetails ana = createMember("CARD-0001");
        lend("LIB-000001", "CARD-0001").expectStatus().isCreated();

        returnCopy("LIB-000001").expectStatus().isNoContent();

        assertThat(listCopies()).singleElement().satisfies(copy -> {
            assertThat(copy.status()).isEqualTo(BookCopyStatus.AVAILABLE);
            assertThat(copy.loan()).isNull();
        });
        assertThat(memberLoans(ana.memberId())).isEmpty();
        assertThat(holdings().availableCopies()).isEqualTo(1);
        returnCopy("LIB-000001").expectStatus().isEqualTo(409);
    }

    @Test
    void aLoanCanBeRenewedUpToTheLimit() {
        addCopy("LIB-000001");
        createMember("CARD-0001");
        lend("LIB-000001", "CARD-0001").expectStatus().isCreated();

        assertThat(renew("LIB-000001").expectStatus().isOk()
                .expectBody(LoanDetails.class).returnResult().getResponseBody().renewals()).isEqualTo(1);
        renew("LIB-000001").expectStatus().isOk();
        renew("LIB-000001").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail")
                .isEqualTo("Book copy LIB-000001 has already been renewed 2 times, which is the most allowed");
    }

    @Test
    void aCopyThatIsOutCannotBeLentAgain() {
        addCopy("LIB-000001");
        createMember("CARD-0001");
        createMember("CARD-0002");
        lend("LIB-000001", "CARD-0001").expectStatus().isCreated();

        lend("LIB-000001", "CARD-0002").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("Book copy LIB-000001 can't be lent because it's LENT");
    }

    @Test
    void aMemberCannotHaveMoreThanThreeBooksOut() {
        createMember("CARD-0001");
        for (int i = 1; i <= 4; i++) {
            addCopy("LIB-00000" + i);
        }
        for (int i = 1; i <= 3; i++) {
            lend("LIB-00000" + i, "CARD-0001").expectStatus().isCreated();
        }

        lend("LIB-000004", "CARD-0001").expectStatus().isEqualTo(409);

        assertThat(holdings().availableCopies()).isEqualTo(1);
    }

    @Test
    void lendsRacingForTheLastFreeSlotLetExactlyOneThrough() throws Exception {
        createMember("CARD-0001");
        for (int i = 1; i <= 7; i++) {
            addCopy("LIB-00000" + i);
        }
        lend("LIB-000001", "CARD-0001").expectStatus().isCreated();
        lend("LIB-000002", "CARD-0001").expectStatus().isCreated();

        List<Integer> statuses = allAtOnce(List.of(
                () -> lendStatus("LIB-000003"), () -> lendStatus("LIB-000004"), () -> lendStatus("LIB-000005"),
                () -> lendStatus("LIB-000006"), () -> lendStatus("LIB-000007")));

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(listCopies()).filteredOn(copy -> copy.status() == BookCopyStatus.LENT).hasSize(3);
    }

    @Test
    void aLendAndADeactivationRacingCannotBothGoThrough() throws Exception {
        addCopy("LIB-000001");
        MemberDetails ana = createMember("CARD-0001");

        List<Integer> statuses = allAtOnce(List.of(
                () -> lendStatus("LIB-000001"),
                () -> client.delete().uri("/api/v1/members/{memberId}", ana.memberId()).exchange()
                        .returnResult().getStatus().value()));

        int lent = statuses.get(0);
        int deactivated = statuses.get(1);
        assertThat(lent == 201 && deactivated == 204).as("lend %s, deactivation %s", lent, deactivated).isFalse();
        MemberDetails after = client.get().uri("/api/v1/members/{memberId}", ana.memberId()).exchange()
                .expectBody(MemberDetails.class).returnResult().getResponseBody();
        if (lent == 201) {
            assertThat(after.status()).isEqualTo(MemberStatus.ACTIVE);
        } else {
            assertThat(memberLoans(ana.memberId())).isEmpty();
        }
    }

    @Test
    void aReturnedBookFreesItsSlotRightAway() {
        createMember("CARD-0001");
        for (int i = 1; i <= 4; i++) {
            addCopy("LIB-00000" + i);
        }
        for (int i = 1; i <= 3; i++) {
            lend("LIB-00000" + i, "CARD-0001").expectStatus().isCreated();
        }

        returnCopy("LIB-000002").expectStatus().isNoContent();

        lend("LIB-000004", "CARD-0001").expectStatus().isCreated();
    }

    @Test
    void anInactiveMemberCannotBorrow() {
        addCopy("LIB-000001");
        MemberDetails ana = createMember("CARD-0001");
        client.delete().uri("/api/v1/members/{memberId}", ana.memberId()).exchange().expectStatus().isNoContent();

        lend("LIB-000001", "CARD-0001").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("Member CARD-0001 is inactive and can't borrow books");
    }

    @Test
    void aMemberWithBooksOutCannotBeDeactivatedUntilTheyReturnThem() {
        addCopy("LIB-000001");
        MemberDetails ana = createMember("CARD-0001");
        lend("LIB-000001", "CARD-0001").expectStatus().isCreated();

        client.delete().uri("/api/v1/members/{memberId}", ana.memberId()).exchange().expectStatus().isEqualTo(409);
        client.patch().uri("/api/v1/members/{memberId}", ana.memberId()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\": \"INACTIVE\"}").exchange().expectStatus().isEqualTo(409);

        returnCopy("LIB-000001").expectStatus().isNoContent();

        client.delete().uri("/api/v1/members/{memberId}", ana.memberId()).exchange().expectStatus().isNoContent();
    }

    @Test
    void aLentCopyCannotBeChangedOrWithdrawnByHand() {
        BookCopyDetails copy = addCopy("LIB-000001");
        createMember("CARD-0001");
        lend("LIB-000001", "CARD-0001").expectStatus().isCreated();

        client.patch().uri("/api/v1/books/{bookId}/copies/{bookCopyId}", dune.bookId(), copy.bookCopyId())
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\": \"AVAILABLE\"}")
                .exchange().expectStatus().isEqualTo(409);
        client.delete().uri("/api/v1/books/{bookId}/copies/{bookCopyId}", dune.bookId(), copy.bookCopyId())
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void answersUnknownNumbersWithNotFound() {
        addCopy("LIB-000001");
        createMember("CARD-0001");

        lend("LIB-999999", "CARD-0001").expectStatus().isNotFound();
        lend("LIB-000001", "CARD-9999").expectStatus().isNotFound();
        returnCopy("LIB-999999").expectStatus().isNotFound();
        client.get().uri("/api/v1/members/{memberId}/loans", UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }

    private int lendStatus(String inventoryNumber) {
        return lend(inventoryNumber, "CARD-0001").returnResult().getStatus().value();
    }

    /** Runs the calls on separate threads, released together so they really overlap. Results keep their order. */
    private static List<Integer> allAtOnce(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = calls.stream()
                    .map(call -> pool.submit(() -> {
                        start.await();
                        return call.call();
                    }))
                    .toList();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private BookDetails createBook() {
        return client.post().uri("/api/v1/books").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": "Dune", "authors": ["Frank Herbert"], "publicationYear": 1965,
                         "language": "English", "genres": ["Science fiction"]}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookDetails.class).returnResult().getResponseBody();
    }

    private BookCopyDetails addCopy(String inventoryNumber) {
        return client.post().uri("/api/v1/books/{bookId}/copies", dune.bookId())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"inventoryNumber\": \"" + inventoryNumber + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(BookCopyDetails.class).returnResult().getResponseBody();
    }

    private MemberDetails createMember(String cardNumber) {
        return client.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .body("{\"cardNumber\": \"" + cardNumber + "\", \"fullName\": \"Member " + cardNumber
                        + "\", \"phone\": \"555-0000\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(MemberDetails.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec lend(String inventoryNumber, String cardNumber) {
        return client.post().uri("/api/v1/loans").contentType(MediaType.APPLICATION_JSON)
                .body("{\"inventoryNumber\": \"" + inventoryNumber + "\", \"cardNumber\": \"" + cardNumber + "\"}")
                .exchange();
    }

    private RestTestClient.ResponseSpec returnCopy(String inventoryNumber) {
        return client.post().uri("/api/v1/returns").contentType(MediaType.APPLICATION_JSON)
                .body("{\"inventoryNumber\": \"" + inventoryNumber + "\"}")
                .exchange();
    }

    private RestTestClient.ResponseSpec renew(String inventoryNumber) {
        return client.post().uri("/api/v1/renewals").contentType(MediaType.APPLICATION_JSON)
                .body("{\"inventoryNumber\": \"" + inventoryNumber + "\"}")
                .exchange();
    }

    private List<BookCopyDetails> listCopies() {
        return client.get().uri("/api/v1/books/{bookId}/copies", dune.bookId()).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<BookCopyDetails>>() {
                })
                .returnResult().getResponseBody();
    }

    private List<LoanDetails> memberLoans(UUID memberId) {
        return client.get().uri("/api/v1/members/{memberId}/loans", memberId).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<LoanDetails>>() {
                })
                .returnResult().getResponseBody();
    }

    private BookSummary holdings() {
        PageResponse<BookSummary> page = client.get().uri("/api/v1/books").exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<BookSummary>>() {
                })
                .returnResult().getResponseBody();
        return page.items().getFirst();
    }
}
