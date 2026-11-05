package io.github.gonzalotguerrero.toshokan.inventory.listing;

import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.AllowFiltering;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface BookListingRepository extends CassandraRepository<BookListingEntry, MapId> {

    /** All titles, sorted by title. */
    Slice<BookListingEntry> findByListingPartition(int listingPartition, Pageable pageable);

    /**
     * Titles with more than {@code minAvailableCopies} copies on the shelf. ALLOW FILTERING sounds scary,
     * but here it only ever scans the one listing partition, never the whole cluster.
     */
    @AllowFiltering
    Slice<BookListingEntry> findByListingPartitionAndAvailableCopiesGreaterThan(int listingPartition,
                                                                                int minAvailableCopies,
                                                                                Pageable pageable);
}
