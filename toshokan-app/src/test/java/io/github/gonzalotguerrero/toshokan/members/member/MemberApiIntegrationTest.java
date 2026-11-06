package io.github.gonzalotguerrero.toshokan.members.member;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Signs members up, edits and deactivates them through the API, and checks what the list and lookups show. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MemberApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CassandraOperations cassandra;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        cassandra.truncate(Member.class);
        cassandra.truncate(MemberByCardNumber.class);
        cassandra.truncate(MemberDirectoryEntry.class);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/toshokan").build();
    }

    @Test
    void createsAMemberAndFetchesItBack() {
        MemberDetails created = createMember("CARD-0001", "  Ana   Pérez ", " Ana@Example.com ");

        assertThat(created.fullName()).isEqualTo("Ana Pérez");
        assertThat(created.email()).isEqualTo("ana@example.com");
        assertThat(created.status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(client.get().uri("/api/v1/members/{memberId}", created.memberId()).exchange()
                .expectStatus().isOk()
                .expectBody(MemberDetails.class).returnResult().getResponseBody())
                .isEqualTo(created);
    }

    @Test
    void refusesACardNumberThatIsAlreadyTaken() {
        createMember("CARD-0001", "Ana Pérez", "ana@example.com");

        client.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .body("{\"cardNumber\": \"CARD-0001\", \"fullName\": \"Bruno\", \"phone\": \"555\"}")
                .exchange()
                .expectStatus().isEqualTo(409);

        assertThat(listAllMembers()).extracting(MemberDetails::fullName).containsExactly("Ana Pérez");
    }

    @Test
    void refusesAMemberWithNoWayToContactThem() {
        client.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .body("{\"cardNumber\": \"CARD-0001\", \"fullName\": \"Ana\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void listsMembersByNameAcrossPages() {
        createMember("CARD-0001", "carla Díaz", "carla@example.com");
        createMember("CARD-0002", "Ana Pérez", "ana@example.com");
        createMember("CARD-0003", "Bruno Gómez", "bruno@example.com");

        PageResponse<MemberDetails> first = listMembers("?pageSize=2");
        PageResponse<MemberDetails> second = listMembers("?pageSize=2&pageToken=" + first.nextPageToken());

        assertThat(first.items()).extracting(MemberDetails::fullName).containsExactly("Ana Pérez", "Bruno Gómez");
        assertThat(second.items()).extracting(MemberDetails::fullName).containsExactly("carla Díaz");
        assertThat(second.nextPageToken()).isNull();
    }

    @Test
    void findsAMemberByCardNumber() {
        createMember("CARD-0001", "Ana Pérez", "ana@example.com");
        MemberDetails bruno = createMember("CARD-0002", "Bruno Gómez", "bruno@example.com");

        assertThat(listMembers("?cardNumber=CARD-0002").items()).containsExactly(bruno);
        assertThat(listMembers("?cardNumber=CARD-9999").items()).isEmpty();
    }

    @Test
    void renamingAMemberMovesThemInTheList() {
        MemberDetails ana = createMember("CARD-0001", "Ana Pérez", "ana@example.com");
        createMember("CARD-0002", "Bruno Gómez", "bruno@example.com");

        updateMember(ana.memberId(), "{\"fullName\": \"Zoe Pérez\", \"phone\": \"555-1234\"}")
                .expectStatus().isOk();

        assertThat(listAllMembers())
                .extracting(MemberDetails::fullName, MemberDetails::phone)
                .containsExactly(tuple("Bruno Gómez", null), tuple("Zoe Pérez", "555-1234"));
    }

    @Test
    void clearingAFieldRemovesItEverywhere() {
        MemberDetails ana = createMember("CARD-0001", "Ana Pérez", "ana@example.com");
        updateMember(ana.memberId(), "{\"phone\": \"555-1234\"}").expectStatus().isOk();

        updateMember(ana.memberId(), "{\"email\": \"\"}").expectStatus().isOk();

        MemberDetails fetched = client.get().uri("/api/v1/members/{memberId}", ana.memberId()).exchange()
                .expectStatus().isOk()
                .expectBody(MemberDetails.class).returnResult().getResponseBody();
        assertThat(fetched.email()).isNull();
        assertThat(listAllMembers()).singleElement().extracting(MemberDetails::email).isNull();
    }

    @Test
    void refusesToClearTheOnlyWayToContactAMember() {
        MemberDetails ana = createMember("CARD-0001", "Ana Pérez", "ana@example.com");

        updateMember(ana.memberId(), "{\"email\": \"\"}").expectStatus().isBadRequest();
    }

    @Test
    void deletingAMemberDeactivatesThemAndTheyCanComeBack() {
        MemberDetails ana = createMember("CARD-0001", "Ana Pérez", "ana@example.com");

        client.delete().uri("/api/v1/members/{memberId}", ana.memberId()).exchange()
                .expectStatus().isNoContent();

        assertThat(listAllMembers()).singleElement()
                .extracting(MemberDetails::status).isEqualTo(MemberStatus.INACTIVE);

        updateMember(ana.memberId(), "{\"status\": \"ACTIVE\"}").expectStatus().isOk();

        assertThat(listMembers("?cardNumber=CARD-0001").items()).singleElement()
                .extracting(MemberDetails::status).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void answersAnUnknownMemberWithNotFound() {
        client.delete().uri("/api/v1/members/{memberId}", UUID.randomUUID()).exchange()
                .expectStatus().isNotFound();
    }

    private MemberDetails createMember(String cardNumber, String fullName, String email) {
        return client.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"cardNumber": "%s", "fullName": "%s", "email": "%s"}
                        """.formatted(cardNumber, fullName, email))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(MemberDetails.class)
                .returnResult()
                .getResponseBody();
    }

    private RestTestClient.ResponseSpec updateMember(UUID memberId, String body) {
        return client.patch().uri("/api/v1/members/{memberId}", memberId)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange();
    }

    private PageResponse<MemberDetails> listMembers(String query) {
        return client.get().uri("/api/v1/members" + query).exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<MemberDetails>>() {
                })
                .returnResult()
                .getResponseBody();
    }

    /** The first page is enough here, since no test creates more members than fit on it. */
    private List<MemberDetails> listAllMembers() {
        return listMembers("").items();
    }
}
