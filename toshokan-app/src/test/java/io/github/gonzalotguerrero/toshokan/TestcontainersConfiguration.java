package io.github.gonzalotguerrero.toshokan;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.cassandra.CassandraContainer;

/**
 * Spins up a throwaway Cassandra 4.1 for the tests (same version as compose.yaml) and points the app at it.
 * Spring caches the test context, so every test class that imports this shares one container instead of
 * starting its own.
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
