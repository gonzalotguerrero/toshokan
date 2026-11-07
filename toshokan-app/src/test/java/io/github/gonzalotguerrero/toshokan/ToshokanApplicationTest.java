package io.github.gonzalotguerrero.toshokan;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// Same setup as the API tests, so this reuses their context and Cassandra instead of starting another one
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ToshokanApplicationTest {

    @Test
    void contextLoads() {
    }

}
