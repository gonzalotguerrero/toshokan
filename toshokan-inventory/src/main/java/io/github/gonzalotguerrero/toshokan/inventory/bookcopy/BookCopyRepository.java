package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.List;
import java.util.UUID;

public interface BookCopyRepository extends CassandraRepository<BookCopy, MapId> {

    List<BookCopy> findByBookId(UUID bookId);
}
