package io.github.gonzalotguerrero.toshokan.inventory.repository;

import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.List;
import java.util.UUID;

/**
 * Only read from. Every write to this table has to be conditional, so they go through {@link NewBookCopyInserter}
 * and {@link BookCopyStatusChanges}.
 */
public interface BookCopyRepository extends CassandraRepository<BookCopy, MapId> {

    List<BookCopy> findByBookId(UUID bookId);

    /** The copies a member has out right now. Goes through the secondary index on {@code member_id}. */
    List<BookCopy> findByMemberId(UUID memberId);

    /** Every copy with the given status, e.g. all the lent ones. Goes through the secondary index on {@code status}. */
    List<BookCopy> findByStatus(BookCopyStatus status);
}
