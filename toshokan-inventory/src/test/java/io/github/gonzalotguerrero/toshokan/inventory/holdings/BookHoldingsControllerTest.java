package io.github.gonzalotguerrero.toshokan.inventory.holdings;

import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.web.ApiExceptionHandlerAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookHoldingsControllerTest {

    @Mock
    private BookHoldingsService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BookHoldingsController(service))
                .setControllerAdvice(new ApiExceptionHandlerAdvice())
                .build();
    }

    @Test
    void usesDefaultsWhenNoParametersAreGiven() throws Exception {
        when(service.listBooks(false, BookHoldingsService.DEFAULT_PAGE_SIZE, null))
                .thenReturn(new PageResponse<>(List.of(), null));

        mockMvc.perform(get("/api/v1/books")).andExpect(status().isOk());

        verify(service).listBooks(false, BookHoldingsService.DEFAULT_PAGE_SIZE, null);
    }

    @Test
    void passesQueryParametersToTheService() throws Exception {
        when(service.listBooks(true, 5, "abc")).thenReturn(new PageResponse<>(List.of(), null));

        mockMvc.perform(get("/api/v1/books").param("available", "true").param("pageSize", "5")
                        .param("pageToken", "abc"))
                .andExpect(status().isOk());

        verify(service).listBooks(true, 5, "abc");
    }

    @Test
    void returnsTheBooksAndTheNextPageToken() throws Exception {
        UUID bookId = UUID.randomUUID();
        BookSummary dune = new BookSummary(bookId, "9780441013593", "Dune", List.of("Frank Herbert"), "Ace", 1965,
                "en", Set.of("science fiction"), 3, 1);
        when(service.listBooks(anyBoolean(), anyInt(), any())).thenReturn(new PageResponse<>(List.of(dune), "next"));

        mockMvc.perform(get("/api/v1/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bookId").value(bookId.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Dune"))
                .andExpect(jsonPath("$.items[0].authors[0]").value("Frank Herbert"))
                .andExpect(jsonPath("$.items[0].publicationYear").value(1965))
                .andExpect(jsonPath("$.items[0].totalCopies").value(3))
                .andExpect(jsonPath("$.items[0].availableCopies").value(1))
                .andExpect(jsonPath("$.nextPageToken").value("next"));
    }

    @Test
    void rejectsANonBooleanAvailableParameter() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("available", "maybe"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void turnsInvalidRequestsIntoProblemResponses() throws Exception {
        when(service.listBooks(anyBoolean(), anyInt(), any()))
                .thenThrow(new InvalidRequestException("pageSize must be between 1 and 100"));

        mockMvc.perform(get("/api/v1/books").param("pageSize", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("pageSize must be between 1 and 100"));
    }
}
