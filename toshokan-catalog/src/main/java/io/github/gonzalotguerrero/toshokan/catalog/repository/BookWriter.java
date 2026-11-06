package io.github.gonzalotguerrero.toshokan.catalog.repository;

import com.datastax.oss.driver.api.core.cql.BatchType;
import io.github.gonzalotguerrero.toshokan.catalog.model.Book;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByAuthor;
import io.github.gonzalotguerrero.toshokan.catalog.model.BookByTitle;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Saves a new book together with its search rows (by title and by author) in one logged batch. Either all of them
 * end up written or none do, so a book can always be found by its title and authors as soon as it exists.
 * Deleting works the same way, so a deleted book never lingers in the search.
 */
@Component
public class BookWriter {

    private final CassandraOperations cassandra;

    public BookWriter(CassandraOperations cassandra) {
        this.cassandra = cassandra;
    }

    public void save(Book book) {
        cassandra.batchOps(BatchType.LOGGED).insert(rowsOf(book)).execute();
    }

    public void delete(Book book) {
        cassandra.batchOps(BatchType.LOGGED).delete(rowsOf(book)).execute();
    }

    private static List<Object> rowsOf(Book book) {
        List<Object> rows = new ArrayList<>();
        rows.add(book);
        rows.add(BookByTitle.of(book));
        rows.addAll(BookByAuthor.of(book));
        return rows;
    }
}
