# Development

## Tests

```shell
mvn verify
```

This runs each module's unit tests, then the integration tests in `toshokan-app`. The integration tests start a
real Cassandra through Testcontainers, so Docker has to be running.

To run a single integration test:

```shell
mvn -pl toshokan-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=LendingApiIntegrationTest
```

## Code layout

Each feature module has the same layer packages: `controller`, `service`, `model`, `repository` and `dto`,
plus `config` where it's needed.

## Schema changes

Tables are created with `spring.cassandra.schema-action=create-if-not-exists`. That creates missing tables and
indexes, but never changes existing ones. So after a column is added to an entity, the app fails at startup
until your local table gets the column too:

```shell
docker exec toshokan-cassandra cqlsh -e "ALTER TABLE toshokan.book_copies_by_book ADD (due_date date)"
```

You can also start from scratch: `docker compose down -v` deletes the Cassandra volume, and everything is created
again on the next start.

## Reference

- [Spring Boot 4.1 reference](https://docs.spring.io/spring-boot/4.1.2/reference/)
- [Spring Data for Apache Cassandra](https://docs.spring.io/spring-data/cassandra/reference/)
- [springdoc-openapi](https://springdoc.org/)
