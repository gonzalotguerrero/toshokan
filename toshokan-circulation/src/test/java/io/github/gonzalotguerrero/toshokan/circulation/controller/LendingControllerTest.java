package io.github.gonzalotguerrero.toshokan.circulation.controller;

import io.github.gonzalotguerrero.toshokan.circulation.dto.LendRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanDetails;
import io.github.gonzalotguerrero.toshokan.circulation.dto.OverdueLoan;
import io.github.gonzalotguerrero.toshokan.circulation.dto.RenewalRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.ReturnRequest;
import io.github.gonzalotguerrero.toshokan.circulation.service.LendingService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.web.ApiExceptionHandlerAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LendingControllerTest {

    private static final LoanDetails LOAN = new LoanDetails(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "LIB-000001", UUID.randomUUID(), Instant.parse("2026-06-15T12:00:00Z"), LocalDate.of(2026, 6, 29), 0,
            false);

    @Mock
    private LendingService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LendingController(service))
                .setControllerAdvice(new ApiExceptionHandlerAdvice())
                .build();
    }

    @Test
    void lendsACopy() throws Exception {
        when(service.lend(new LendRequest("LIB-000001", "CARD-0001"))).thenReturn(LOAN);

        mockMvc.perform(post("/api/v1/loans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryNumber\": \"LIB-000001\", \"cardNumber\": \"CARD-0001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loanId").value(LOAN.loanId().toString()))
                .andExpect(jsonPath("$.dueDate").value("2026-06-29"))
                .andExpect(jsonPath("$.overdue").value(false));
    }

    @Test
    void requiresBothNumbersToLend() throws Exception {
        mockMvc.perform(post("/api/v1/loans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryNumber\": \"LIB-000001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cardNumber").exists());

        verifyNoInteractions(service);
    }

    @Test
    void answersARefusedLoanWithConflict() throws Exception {
        when(service.lend(new LendRequest("LIB-000001", "CARD-0001")))
                .thenThrow(new ConflictException("Member CARD-0001 already has 3 books out, which is the most allowed"));

        mockMvc.perform(post("/api/v1/loans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryNumber\": \"LIB-000001\", \"cardNumber\": \"CARD-0001\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Member CARD-0001 already has 3 books out, which is the most allowed"));
    }

    @Test
    void returnsACopy() throws Exception {
        mockMvc.perform(post("/api/v1/returns").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryNumber\": \"LIB-000001\"}"))
                .andExpect(status().isNoContent());

        verify(service).returnCopy(new ReturnRequest("LIB-000001"));
    }

    @Test
    void answersReturningACopyThatIsNotLentWithConflict() throws Exception {
        doThrow(new ConflictException("Book copy LIB-000001 isn't lent out"))
                .when(service).returnCopy(new ReturnRequest("LIB-000001"));

        mockMvc.perform(post("/api/v1/returns").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryNumber\": \"LIB-000001\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void renewsALoan() throws Exception {
        when(service.renew(new RenewalRequest("LIB-000001"))).thenReturn(LOAN);

        mockMvc.perform(post("/api/v1/renewals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryNumber\": \"LIB-000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryNumber").value("LIB-000001"));
    }

    @Test
    void listsAMembersLoans() throws Exception {
        when(service.memberLoans(LOAN.memberId())).thenReturn(List.of(LOAN));

        mockMvc.perform(get("/api/v1/members/{memberId}/loans", LOAN.memberId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].inventoryNumber").value("LIB-000001"));
    }

    @Test
    void listsOverdueLoans() throws Exception {
        when(service.overdueLoans()).thenReturn(List.of(new OverdueLoan(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "LIB-000001", "Kokoro", UUID.randomUUID(), "CARD-0001", "Ana Pérez",
                "ana@example.com", "+81 3 1234 5678", LocalDate.of(2026, 6, 10), 5)));

        mockMvc.perform(get("/api/v1/loans/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Kokoro"))
                .andExpect(jsonPath("$[0].email").value("ana@example.com"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-06-10"))
                .andExpect(jsonPath("$[0].daysOverdue").value(5));
    }
}
