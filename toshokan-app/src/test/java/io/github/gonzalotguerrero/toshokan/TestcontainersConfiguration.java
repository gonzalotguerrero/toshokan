package io.github.gonzalotguerrero.toshokan;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.cassandra.CassandraContainer;

/**
 * Spins up a throwaway Cassandra 4.1 for the tests (same version as compose.yaml) and points the app at it.
 * The container belongs to the Spring context, so test classes only share it when they also share a cached
 * context. Any difference in setup, even just the {@code webEnvironment}, builds a new context and starts another
 * Cassandra. That's why every integration test uses the same {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    CassandraContainer cassandraContainer() {
        return new CassandraContainer("cassandra:4.1")
                .withInitScript("cassandra/init-keyspace.cql");
    }
}
