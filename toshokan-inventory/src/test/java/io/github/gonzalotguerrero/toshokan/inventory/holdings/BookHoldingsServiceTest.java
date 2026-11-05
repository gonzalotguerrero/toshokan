package io.github.gonzalotguerrero.toshokan.inventory.holdings;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.github.gonzalotguerrero.toshokan.inventory.holdings.BookHoldings.HOLDINGS_PARTITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookHoldingsServiceTest {

    private static final BookHoldings DUNE = BookHoldings.of(UUID.randomUUID(), "9780441013593", "Dune",
            List.of("Frank Herbert"), "Ace", 1965, "en", Set.of("science fiction"), 3, 1);

    @Mock
    private BookHoldingsRepository repository;

    @InjectMocks
    private BookHoldingsService service;

    @Test
    void listsAllTitlesWhenNotFilteringByAvailability() {
        when(repository.findByHoldingsPartition(eq(HOLDINGS_PARTITION), any())).thenReturn(lastSlice(List.of(DUNE)));

        PageResponse<BookSummary> page = service.listBooks(false, 20, null);

        assertThat(page.items()).singleElement().satisfies(book -> {
            assertThat(book.bookId()).isEqualTo(DUNE.bookId());
            assertThat(book.title()).isEqualTo("Dune");
            assertThat(book.authors()).containsExactly("Frank Herbert");
            assertThat(book.totalCopies()).isEqualTo(3);
            assertThat(book.availableCopies()).isEqualTo(1);
        });
        assertThat(page.nextPageToken()).isNull();
    }

    @Test
    void listsOnlyTitlesWithACopyOnTheShelfWhenFilteringByAvailability() {
        when(repository.findByHoldingsPartitionAndAvailableCopiesGreaterThan(eq(HOLDINGS_PARTITION), eq(0), any()))
                .thenReturn(lastSlice(List.of(DUNE)));

        service.listBooks(true, 20, null);

        verify(repository).findByHoldingsPartitionAndAvailableCopiesGreaterThan(eq(HOLDINGS_PARTITION), eq(0), any());
    }

    @Test
    void requestsTheGivenPageSizeStartingFromTheFirstPage() {
        when(repository.findByHoldingsPartition(eq(HOLDINGS_PARTITION), any())).thenReturn(lastSlice(List.of()));

        service.listBooks(false, 5, null);

        CassandraPageRequest request = capturePageRequest();
        assertThat(request.getPageSize()).isEqualTo(5);
        assertThat(request.getPagingState()).isNull();
    }

    @Test
    void resumesFromThePagingStateInThePageToken() {
        ByteBuffer state = ByteBuffer.wrap(new byte[]{7, 8, 9});
        when(repository.findByHoldingsPartition(eq(HOLDINGS_PARTITION), any())).thenReturn(lastSlice(List.of()));

        service.listBooks(false, 20, PageTokens.encode(state));

        assertThat(capturePageRequest().getPagingState()).isEqualTo(state);
    }

    @Test
    void returnsATokenForTheNextPageWhenThereIsOne() {
        ByteBuffer nextState = ByteBuffer.wrap(new byte[]{4, 2});
        SliceImpl<BookHoldings> slice = new SliceImpl<>(List.of(DUNE),
                CassandraPageRequest.of(PageRequest.of(0, 1), nextState), true);
        when(repository.findByHoldingsPartition(eq(HOLDINGS_PARTITION), any())).thenReturn(slice);

        PageResponse<BookSummary> page = service.listBooks(false, 1, null);

        assertThat(page.nextPageToken()).isEqualTo(PageTokens.encode(nextState));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, BookHoldingsService.MAX_PAGE_SIZE + 1})
    void rejectsOutOfRangePageSizes(int pageSize) {
        assertThatThrownBy(() -> service.listBooks(false, pageSize, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("pageSize must be between 1 and " + BookHoldingsService.MAX_PAGE_SIZE);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsATokenThatCassandraDoesNotAccept() {
        when(repository.findByHoldingsPartition(anyInt(), any())).thenThrow(new InvalidDataAccessApiUsageException("bad state"));

        assertThatThrownBy(() -> service.listBooks(false, 20, PageTokens.encode(ByteBuffer.wrap(new byte[]{1}))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Invalid page token");
    }

    @Test
    void propagatesDatabaseErrorsThatAreNotCausedByTheToken() {
        when(repository.findByHoldingsPartition(anyInt(), any())).thenThrow(new InvalidDataAccessApiUsageException("boom"));

        assertThatThrownBy(() -> service.listBooks(false, 20, null))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    private CassandraPageRequest capturePageRequest() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByHoldingsPartition(eq(HOLDINGS_PARTITION), captor.capture());
        return (CassandraPageRequest) captor.getValue();
    }

    private static SliceImpl<BookHoldings> lastSlice(List<BookHoldings> content) {
        return new SliceImpl<>(content, CassandraPageRequest.first(20), false);
    }
}
