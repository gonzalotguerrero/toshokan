package io.github.gonzalotguerrero.toshokan.circulation;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanDetails;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanHistoryEntry;
import io.github.gonzalotguerrero.toshokan.circulation.dto.OverdueLoan;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByCopy;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByMember;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanHistoryWriter;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyByInventoryNumber;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookHoldings;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberByCardNumber;
import io.github.gonzalotguerrero.toshokan.members.model.MemberDirectoryEntry;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loan histories and the overdue list through the API against a real Cassandra, including a history row left
 * behind by a lend that never went through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LoanHistoryApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CassandraOperations cassandra;

    @Autowired
    private LoanHistoryWriter historyWriter;

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
    void anActiveLoanShowsUpInBothHistories() {
        BookCopyDetails copy = addCopy("LIB-000001");
        MemberDetails ana = createMember("CARD-0001");
        LoanDetails loan = lend("LIB-000001", "CARD-0001");

        LoanHistoryEntry byCopy = copyHistory(copy, 20, null).items().getFirst();
        LoanHistoryEntry byMember = memberHistory(ana.memberId(), 20, null).items().getFirst();

        assertThat(byCopy).isEqualTo(byMember);
        assertThat(byCopy.loanId()).isEqualTo(loan.loanId());
        assertThat(byCopy.lentAt()).isEqualTo(loan.lentAt());
        assertThat(byCopy.dueDate()).isEqualTo(loan.dueDate());
        assertThat(byCopy.returnedAt()).isNull();
    }

    @Test
    void aRenewalShowsInTheHistoryWhileTheLoanIsActive() {
        BookCopyDetails copy = addCopy("LIB-000001");
        createMember("CARD-0001");
        lend("LIB-000001", "CARD-0001");
        post("/api/v1/renewals", "{\"inventoryNumber\": \"LIB-000001\"}").expectStatus().isOk();

        assertThat(copyHistory(copy, 20, null).items().getFirst().renewals()).isEqualTo(1);
    }

    @Test
    void aReturnedLoanKeepsItsReturnDate() {
        BookCopyDetails copy = addCopy("LIB-000001");
        createMember("CARD-0001");
        lend("LIB-000001", "CARD-0001");
        post("/api/v1/returns", "{\"inventoryNumber\": \"LIB-000001\"}").expectStatus().isNoContent();

        assertThat(copyHistory(copy, 20, null).items()).singleElement().satisfies(entry -> {
            assertThat(entry.returnedAt()).isNotNull();
            assertThat(entry.returnedLate()).isFalse();
            assertThat(entry.overdue()).isFalse();
        });
    }

    @Test
    void aMembersHistoryIsNewestFirstAndComesInPages() {
        MemberDetails ana = createMember("CARD-0001");
        for (String number : List.of("LIB-000001", "LIB-000002", "LIB-000003")) {
            addCopy(number);
            lend(number, "CARD-0001");
            post("/api/v1/returns", "{\"inventoryNumber\": \"" + number + "\"}").expectStatus().isNoContent();
        }

        PageResponse<LoanHistoryEntry> first = memberHistory(ana.memberId(), 2, null);
        PageResponse<LoanHistoryEntry> second = memberHistory(ana.memberId(), 2, first.nextPageToken());

        assertThat(first.items()).extracting(LoanHistoryEntry::inventoryNumber)
                .containsExactly("LIB-000003", "LIB-000002");
        assertThat(second.items()).extracting(LoanHistoryEntry::inventoryNumber).containsExactly("LIB-000001");
    }

    @Test
    void aLendThatNeverWentThroughIsLeftOutOfTheHistory() {
        BookCopyDetails copy = addCopy("LIB-000001");
        MemberDetails ana = createMember("CARD-0001");
        // What a lend leaves behind if it stops right after writing the history
        historyWriter.save(new LoanByCopy(copy.bookCopyId(), Instant.parse("2026-01-05T10:00:00Z"), UUID.randomUUID(),
                dune.bookId(), "LIB-000001", ana.memberId(), LocalDate.of(2026, 1, 19), 0, null));

        assertThat(copyHistory(copy, 20, null).items()).isEmpty();
        assertThat(memberHistory(ana.memberId(), 20, null).items()).isEmpty();
    }

    @Test
    void theOverdueListShowsWhoToChaseAndHowLate() {
        BookCopyDetails late = addCopy("LIB-000001");
        addCopy("LIB-000002");
        MemberDetails ana = createMember("CARD-0001");
        lend("LIB-000001", "CARD-0001");
        lend("LIB-000002", "CARD-0001");
        makeDueOn(late, LocalDate.now(clock).minusDays(3));

        List<OverdueLoan> overdue = client.get().uri("/api/v1/loans/overdue").exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<OverdueLoan>>() {
                })
                .returnResult().getResponseBody();

        assertThat(overdue).singleElement().satisfies(loan -> {
            assertThat(loan.inventoryNumber()).isEqualTo("LIB-000001");
            assertThat(loan.title()).isEqualTo("Dune");
            assertThat(loan.cardNumber()).isEqualTo("CARD-0001");
            assertThat(loan.fullName()).isEqualTo(ana.fullName());
            assertThat(loan.phone()).isEqualTo("555-0000");
            assertThat(loan.daysOverdue()).isEqualTo(3);
        });
        assertThat(copyHistory(late, 20, null).items().getFirst().overdue()).isTrue();
    }

    @Test
    void answersUnknownCopiesAndMembersWithNotFound() {
        client.get().uri("/api/v1/books/{bookId}/copies/{bookCopyId}/loan-history", dune.bookId(), UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/members/{memberId}/loan-history", UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
    }

    /**
     * Moves a loan's due date straight in the table. It's a conditional write because every other write to this
     * table is one too, and mixing the two kinds on the same row can lose updates.
     */
    private void makeDueOn(BookCopyDetails copy, LocalDate dueDate) {
        boolean applied = cassandra.getCqlOperations().execute(
                "UPDATE book_copies_by_book SET due_date = ? WHERE book_id = ? AND book_copy_id = ? IF status = 'LENT'",
                dueDate, copy.bookId(), copy.bookCopyId());
        assertThat(applied).isTrue();
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
        return post("/api/v1/books/" + dune.bookId() + "/copies", "{\"inventoryNumber\": \"" + inventoryNumber + "\"}")
                .expectStatus().isCreated()
                .expectBody(BookCopyDetails.class).returnResult().getResponseBody();
    }

    private MemberDetails createMember(String cardNumber) {
        return post("/api/v1/members", "{\"cardNumber\": \"" + cardNumber + "\", \"fullName\": \"Member "
                + cardNumber + "\", \"phone\": \"555-0000\"}")
                .expectStatus().isCreated()
                .expectBody(MemberDetails.class).returnResult().getResponseBody();
    }

    private LoanDetails lend(String inventoryNumber, String cardNumber) {
        return post("/api/v1/loans", "{\"inventoryNumber\": \"" + inventoryNumber + "\", \"cardNumber\": \""
                + cardNumber + "\"}")
                .expectStatus().isCreated()
                .expectBody(LoanDetails.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec post(String uri, String body) {
        return client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body).exchange();
    }

    private PageResponse<LoanHistoryEntry> copyHistory(BookCopyDetails copy, int pageSize, String pageToken) {
        return client.get()
                .uri(builder -> builder.path("/api/v1/books/{bookId}/copies/{bookCopyId}/loan-history")
                        .queryParam("pageSize", pageSize)
                        .queryParamIfPresent("pageToken", Optional.ofNullable(pageToken))
                        .build(copy.bookId(), copy.bookCopyId()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<LoanHistoryEntry>>() {
                })
                .returnResult().getResponseBody();
    }

    private PageResponse<LoanHistoryEntry> memberHistory(UUID memberId, int pageSize, String pageToken) {
        return client.get()
                .uri(builder -> builder.path("/api/v1/members/{memberId}/loan-history")
                        .queryParam("pageSize", pageSize)
                        .queryParamIfPresent("pageToken", Optional.ofNullable(pageToken))
                        .build(memberId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<LoanHistoryEntry>>() {
                })
                .returnResult().getResponseBody();
    }
}
