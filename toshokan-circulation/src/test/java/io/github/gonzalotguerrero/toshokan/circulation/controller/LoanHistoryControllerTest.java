package io.github.gonzalotguerrero.toshokan.circulation.controller;

import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanHistoryEntry;
import io.github.gonzalotguerrero.toshokan.circulation.service.LoanHistoryService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.web.ApiExceptionHandlerAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LoanHistoryControllerTest {

    private static final LoanHistoryEntry RETURNED = new LoanHistoryEntry(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "LIB-000001", UUID.randomUUID(), Instant.parse("2026-05-01T10:00:00Z"),
            LocalDate.of(2026, 5, 15), 0, Instant.parse("2026-05-16T10:00:00Z"), true, false);

    @Mock
    private LoanHistoryService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LoanHistoryController(service))
                .setControllerAdvice(new ApiExceptionHandlerAdvice())
                .build();
    }

    @Test
    void showsAMembersHistoryWithTheDefaultPageSize() throws Exception {
        when(service.memberHistory(RETURNED.memberId(), 20, null))
                .thenReturn(new PageResponse<>(List.of(RETURNED), "next"));

        mockMvc.perform(get("/api/v1/members/{memberId}/loan-history", RETURNED.memberId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].inventoryNumber").value("LIB-000001"))
                .andExpect(jsonPath("$.items[0].returnedLate").value(true))
                .andExpect(jsonPath("$.nextPageToken").value("next"));
    }

    @Test
    void showsACopysHistoryPageByPage() throws Exception {
        when(service.copyHistory(RETURNED.bookId(), RETURNED.bookCopyId(), 5, "abc"))
                .thenReturn(new PageResponse<>(List.of(RETURNED), null));

        mockMvc.perform(get("/api/v1/books/{bookId}/copies/{bookCopyId}/loan-history", RETURNED.bookId(),
                        RETURNED.bookCopyId()).param("pageSize", "5").param("pageToken", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].loanId").value(RETURNED.loanId().toString()));
    }

    @Test
    void answersAnUnknownCopyWithNotFound() throws Exception {
        UUID bookId = UUID.randomUUID();
        UUID copyId = UUID.randomUUID();
        when(service.copyHistory(bookId, copyId, 20, null))
                .thenThrow(new NotFoundException("Book " + bookId + " has no copy with id " + copyId));

        mockMvc.perform(get("/api/v1/books/{bookId}/copies/{bookCopyId}/loan-history", bookId, copyId))
                .andExpect(status().isNotFound());
    }
}
