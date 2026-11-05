package io.github.gonzalotguerrero.toshokan.inventory.listing;

import io.github.gonzalotguerrero.toshokan.catalog.book.BookDetails;
import io.github.gonzalotguerrero.toshokan.inventory.bookcopy.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.bookcopy.BookCopyRepository;
import io.github.gonzalotguerrero.toshokan.inventory.bookcopy.BookCopyStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.mapping.MapId;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookListingRefresherTest {

    private static final BookDetails DUNE = new BookDetails(UUID.randomUUID(), "9780441013593", "Dune",
            List.of("Frank Herbert"), "Ace", 1965, "English", Set.of("Science fiction"), Instant.now());

    @Mock
    private BookListingRepository listing;

    @Mock
    private BookCopyRepository copies;

    @InjectMocks
    private BookListingRefresher refresher;

    @Test
    void countsAllCopiesAndTheAvailableOnes() {
        when(copies.findByBookId(DUNE.bookId()))
                .thenReturn(List.of(copy(BookCopyStatus.AVAILABLE), copy(BookCopyStatus.LENT),
                        copy(BookCopyStatus.AVAILABLE)));

        refresher.refresh(DUNE);

        ArgumentCaptor<BookListingEntry> saved = ArgumentCaptor.forClass(BookListingEntry.class);
        verify(listing).save(saved.capture());
        assertThat(saved.getValue().totalCopies()).isEqualTo(3);
        assertThat(saved.getValue().availableCopies()).isEqualTo(2);
    }

    @Test
    void copiesTheBookDetailsIntoTheListing() {
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of(copy(BookCopyStatus.AVAILABLE)));

        refresher.refresh(DUNE);

        ArgumentCaptor<BookListingEntry> saved = ArgumentCaptor.forClass(BookListingEntry.class);
        verify(listing).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(BookListingEntry.of(DUNE.bookId(), "9780441013593", "Dune",
                List.of("Frank Herbert"), "Ace", 1965, "English", Set.of("Science fiction"), 1, 1));
    }

    @Test
    void removesTheBookFromTheListingWhenItHasNoCopiesLeft() {
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of());

        refresher.refresh(DUNE);

        ArgumentCaptor<MapId> deleted = ArgumentCaptor.forClass(MapId.class);
        verify(listing).deleteById(deleted.capture());
        assertThat(deleted.getValue()).containsEntry("listingPartition", BookListingEntry.LISTING_PARTITION)
                .containsEntry("sortTitle", "dune")
                .containsEntry("bookId", DUNE.bookId());
        verify(listing, never()).save(any());
    }

    private static BookCopy copy(BookCopyStatus status) {
        return new BookCopy(DUNE.bookId(), UUID.randomUUID(), "LIB-" + UUID.randomUUID(), status, Instant.now());
    }
}
