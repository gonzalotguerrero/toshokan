package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyByInventoryNumber;
import org.springframework.data.cassandra.repository.CassandraRepository;

/** Only read from, to find a copy from the number on its label. Writes go through {@link InventoryNumberClaims}. */
public interface BookCopyByInventoryNumberRepository extends CassandraRepository<BookCopyByInventoryNumber, String> {
}
