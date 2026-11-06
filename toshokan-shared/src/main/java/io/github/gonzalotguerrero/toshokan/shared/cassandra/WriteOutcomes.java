package io.github.gonzalotguerrero.toshokan.shared.cassandra;

import org.springframework.data.cassandra.CassandraAuthenticationException;
import org.springframework.data.cassandra.CassandraInsufficientReplicasAvailableException;
import org.springframework.data.cassandra.CassandraInvalidConfigurationInQueryException;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.CassandraQuerySyntaxException;
import org.springframework.data.cassandra.CassandraTypeMismatchException;
import org.springframework.data.cassandra.CassandraUnauthorizedException;

import java.util.List;

/**
 * Tells apart a write that certainly didn't happen from one that might have.
 *
 * <p>This matters whenever a failed write leads to undoing something else, like freeing a claim on an ISBN. If the
 * write might still land (a timeout, a dropped connection), undoing the claim could leave a book without one, and
 * a second book could then take the same ISBN. So anything not on the short list below counts as "might have
 * happened", and the claim is kept. A claim kept by mistake is harmless: it can be taken over later (see
 * {@link StaleClaims}).
 */
public final class WriteOutcomes {

    /*
     * Errors Cassandra raises before writing anything: the coordinator saw too few replicas alive, the statement
     * itself was wrong, or we weren't allowed to run it. A type mismatch happens in the driver, before sending.
     */
    private static final List<Class<? extends RuntimeException>> NEVER_APPLIED = List.of(
            CassandraInsufficientReplicasAvailableException.class,
            CassandraInvalidQueryException.class,
            CassandraQuerySyntaxException.class,
            CassandraInvalidConfigurationInQueryException.class,
            CassandraTypeMismatchException.class,
            CassandraAuthenticationException.class,
            CassandraUnauthorizedException.class);

    private WriteOutcomes() {
    }

    /** True only when the failed write is known not to have been applied, not even partly. */
    public static boolean definitelyNotApplied(RuntimeException failure) {
        return NEVER_APPLIED.stream().anyMatch(type -> type.isInstance(failure));
    }
}
