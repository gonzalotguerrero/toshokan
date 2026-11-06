package io.github.gonzalotguerrero.toshokan.circulation.repository;

import com.datastax.oss.driver.api.core.cql.BatchType;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByCopy;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByMember;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Saves a loan to both history tables in one logged batch, so the copy's history and the member's history always
 * agree. Saving the same loan again overwrites it, which is how a return fills in {@code returned_at}.
 */
@Component
public class LoanHistoryWriter {

    // Nulls are written on purpose, so a row always ends up exactly as given
    private static final InsertOptions INSERT_NULLS = InsertOptions.builder().withInsertNulls().build();

    private final CassandraOperations cassandra;

    public LoanHistoryWriter(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    public void save(LoanByCopy loan) {
        cassandra.batchOps(BatchType.LOGGED).insert(List.of(loan, LoanByMember.of(loan)), INSERT_NULLS).execute();
    }
}
