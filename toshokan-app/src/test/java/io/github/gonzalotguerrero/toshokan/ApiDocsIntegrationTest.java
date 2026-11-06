package io.github.gonzalotguerrero.toshokan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Checks that the generated API description is served and covers every module, so the README's links to it keep
 * working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApiDocsIntegrationTest {

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port + "/toshokan").build();
    }

    @Test
    void describesTheEndpointsOfEveryModule() {
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths['/api/v1/books/search']").exists()
                .jsonPath("$.paths['/api/v1/books/{bookId}/copies']").exists()
                .jsonPath("$.paths['/api/v1/members']").exists()
                .jsonPath("$.paths['/api/v1/loans/overdue']").exists();
    }

    @Test
    void servesSwaggerUi() {
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }
}
