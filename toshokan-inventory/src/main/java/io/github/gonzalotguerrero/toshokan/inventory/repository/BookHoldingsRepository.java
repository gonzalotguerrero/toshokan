package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookHoldings;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.AllowFiltering;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface BookHoldingsRepository extends CassandraRepository<BookHoldings, MapId> {

    /** All titles, sorted by title. */
    Slice<BookHoldings> findByHoldingsPartition(int holdingsPartition, Pageable pageable);

    /**
     * Titles with more than {@code minAvailableCopies} copies on the shelf. ALLOW FILTERING sounds scary,
     * but here it only ever scans the one holdings partition, never the whole cluster.
     */
    @AllowFiltering
    Slice<BookHoldings> findByHoldingsPartitionAndAvailableCopiesGreaterThan(int holdingsPartition,
                                                                                int minAvailableCopies,
                                                                                Pageable pageable);
}
