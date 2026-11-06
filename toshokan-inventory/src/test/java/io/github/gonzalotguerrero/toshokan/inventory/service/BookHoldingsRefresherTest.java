package io.github.gonzalotguerrero.toshokan.inventory.service;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopy;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookHoldings;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookCopyRepository;
import io.github.gonzalotguerrero.toshokan.inventory.repository.BookHoldingsRepository;
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
class BookHoldingsRefresherTest {

    private static final BookDetails DUNE = new BookDetails(UUID.randomUUID(), "9780441013593", "Dune",
            List.of("Frank Herbert"), "Ace", 1965, "English", Set.of("Science fiction"), Instant.now());

    @Mock
    private BookHoldingsRepository holdings;

    @Mock
    private BookCopyRepository copies;

    @InjectMocks
    private BookHoldingsRefresher refresher;

    @Test
    void countsAllCopiesAndTheAvailableOnes() {
        when(copies.findByBookId(DUNE.bookId()))
                .thenReturn(List.of(copy(BookCopyStatus.AVAILABLE), copy(BookCopyStatus.LENT),
                        copy(BookCopyStatus.AVAILABLE)));

        refresher.refresh(DUNE);

        ArgumentCaptor<BookHoldings> saved = ArgumentCaptor.forClass(BookHoldings.class);
        verify(holdings).save(saved.capture());
        assertThat(saved.getValue().totalCopies()).isEqualTo(3);
        assertThat(saved.getValue().availableCopies()).isEqualTo(2);
    }

    @Test
    void copiesTheBookDetailsIntoTheHoldings() {
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of(copy(BookCopyStatus.AVAILABLE)));

        refresher.refresh(DUNE);

        ArgumentCaptor<BookHoldings> saved = ArgumentCaptor.forClass(BookHoldings.class);
        verify(holdings).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(BookHoldings.of(DUNE.bookId(), "9780441013593", "Dune",
                List.of("Frank Herbert"), "Ace", 1965, "English", Set.of("Science fiction"), 1, 1));
    }

    @Test
    void removesTheBookFromTheHoldingsWhenItHasNoCopiesLeft() {
        when(copies.findByBookId(DUNE.bookId())).thenReturn(List.of());

        refresher.refresh(DUNE);

        ArgumentCaptor<MapId> deleted = ArgumentCaptor.forClass(MapId.class);
        verify(holdings).deleteById(deleted.capture());
        assertThat(deleted.getValue()).containsEntry("holdingsPartition", BookHoldings.HOLDINGS_PARTITION)
                .containsEntry("sortTitle", "dune")
                .containsEntry("bookId", DUNE.bookId());
        verify(holdings, never()).save(any());
    }

    @Test
    void leavesWithdrawnCopiesOutOfTheCounts() {
        when(copies.findByBookId(DUNE.bookId()))
                .thenReturn(List.of(copy(BookCopyStatus.AVAILABLE), copy(BookCopyStatus.WITHDRAWN),
                        copy(BookCopyStatus.DAMAGED)));

        refresher.refresh(DUNE);

        ArgumentCaptor<BookHoldings> saved = ArgumentCaptor.forClass(BookHoldings.class);
        verify(holdings).save(saved.capture());
        assertThat(saved.getValue().totalCopies()).isEqualTo(2);
        assertThat(saved.getValue().availableCopies()).isEqualTo(1);
    }

    @Test
    void removesTheBookFromTheHoldingsWhenEveryCopyIsWithdrawn() {
        when(copies.findByBookId(DUNE.bookId()))
                .thenReturn(List.of(copy(BookCopyStatus.WITHDRAWN), copy(BookCopyStatus.WITHDRAWN)));

        refresher.refresh(DUNE);

        verify(holdings).deleteById(any(MapId.class));
        verify(holdings, never()).save(any());
    }

    private static BookCopy copy(BookCopyStatus status) {
        return new BookCopy(DUNE.bookId(), UUID.randomUUID(), "LIB-" + UUID.randomUUID(), status, Instant.now());
    }
}
