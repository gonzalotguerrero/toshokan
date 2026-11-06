package io.github.gonzalotguerrero.toshokan.shared.cassandra;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.cassandra.CassandraConnectionFailureException;
import org.springframework.data.cassandra.CassandraDriverTimeoutException;
import org.springframework.data.cassandra.CassandraInsufficientReplicasAvailableException;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.CassandraWriteTimeoutException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WriteOutcomesTest {

    @Test
    void errorsRaisedBeforeWritingMeanNothingWasApplied() {
        assertThat(WriteOutcomes.definitelyNotApplied(
                new CassandraInsufficientReplicasAvailableException(2, 1, "unavailable", null))).isTrue();
        assertThat(WriteOutcomes.definitelyNotApplied(new CassandraInvalidQueryException("bad query", null)))
                .isTrue();
    }

    @Test
    void timeoutsAndDroppedConnectionsMightStillHaveBeenApplied() {
        assertThat(WriteOutcomes.definitelyNotApplied(new CassandraWriteTimeoutException("BATCH_LOG", "timed out",
                null))).isFalse();
        assertThat(WriteOutcomes.definitelyNotApplied(new CassandraWriteTimeoutException("CAS", "timed out", null)))
                .isFalse();
        assertThat(WriteOutcomes.definitelyNotApplied(new CassandraDriverTimeoutException("client timeout", null)))
                .isFalse();
        assertThat(WriteOutcomes.definitelyNotApplied(new CassandraConnectionFailureException(Map.of(),
                "connection lost", null))).isFalse();
        assertThat(WriteOutcomes.definitelyNotApplied(new QueryTimeoutException("timeout"))).isFalse();
    }

    @Test
    void anythingUnexpectedCountsAsMaybeApplied() {
        assertThat(WriteOutcomes.definitelyNotApplied(new IllegalStateException("surprise"))).isFalse();
    }
}
