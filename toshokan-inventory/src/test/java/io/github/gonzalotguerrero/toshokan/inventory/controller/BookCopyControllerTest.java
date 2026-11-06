package io.github.gonzalotguerrero.toshokan.inventory.controller;

import io.github.gonzalotguerrero.toshokan.inventory.dto.AddBookCopyRequest;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookCopyControllerTest {

    private static final UUID BOOK_ID = UUID.randomUUID();

    @Mock
    private BookCopyService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BookCopyController(service))
                .setControllerAdvice(new ApiExceptionHandlerAdvice())
                .build();
    }

    @Test
    void addsACopy() throws Exception {
        BookCopyDetails copy = new BookCopyDetails(UUID.randomUUID(), BOOK_ID, "LIB-000123", BookCopyStatus.AVAILABLE,
                Instant.parse("2026-06-15T10:00:00Z"));
        when(service.addBookCopy(BOOK_ID, new AddBookCopyRequest("LIB-000123"))).thenReturn(copy);

        mockMvc.perform(post("/api/v1/books/{bookId}/copies", BOOK_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inventoryNumber\": \"LIB-000123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookCopyId").value(copy.bookCopyId().toString()))
                .andExpect(jsonPath("$.bookId").value(BOOK_ID.toString()))
                .andExpect(jsonPath("$.inventoryNumber").value("LIB-000123"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        verify(service).addBookCopy(BOOK_ID, new AddBookCopyRequest("LIB-000123"));
    }

    @Test
    void requiresAnInventoryNumber() throws Exception {
        mockMvc.perform(post("/api/v1/books/{bookId}/copies", BOOK_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inventoryNumber\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.inventoryNumber").exists());

        verifyNoInteractions(service);
    }

    @Test
    void answersAnUnknownBookWithNotFound() throws Exception {
        when(service.addBookCopy(eq(BOOK_ID), any())).thenThrow(new NotFoundException("No book with id " + BOOK_ID));

        mockMvc.perform(post("/api/v1/books/{bookId}/copies", BOOK_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inventoryNumber\": \"LIB-000123\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void answersAnInventoryNumberInUseWithConflict() throws Exception {
        when(service.addBookCopy(eq(BOOK_ID), any()))
                .thenThrow(new ConflictException("Inventory number LIB-000123 is already in use"));

        mockMvc.perform(post("/api/v1/books/{bookId}/copies", BOOK_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inventoryNumber\": \"LIB-000123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Inventory number LIB-000123 is already in use"));
    }

    @Test
    void listsTheCopiesOfABook() throws Exception {
        BookCopyDetails copy = copyWithStatus(BookCopyStatus.WITHDRAWN);
        when(service.listBookCopies(BOOK_ID)).thenReturn(List.of(copy));

        mockMvc.perform(get("/api/v1/books/{bookId}/copies", BOOK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookCopyId").value(copy.bookCopyId().toString()))
                .andExpect(jsonPath("$[0].status").value("WITHDRAWN"));
    }

    @Test
    void changesTheStatusOfACopy() throws Exception {
        BookCopyDetails damaged = copyWithStatus(BookCopyStatus.DAMAGED);
        when(service.changeStatus(BOOK_ID, damaged.bookCopyId(), BookCopyStatus.DAMAGED)).thenReturn(damaged);

        mockMvc.perform(patch("/api/v1/books/{bookId}/copies/{bookCopyId}", BOOK_ID, damaged.bookCopyId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"DAMAGED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DAMAGED"));
    }

    @Test
    void requiresAStatusWhenChangingIt() throws Exception {
        mockMvc.perform(patch("/api/v1/books/{bookId}/copies/{bookCopyId}", BOOK_ID, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").exists());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsAStatusThatDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/v1/books/{bookId}/copies/{bookCopyId}", BOOK_ID, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"BORROWED\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void answersAForbiddenStatusChangeWithConflict() throws Exception {
        UUID bookCopyId = UUID.randomUUID();
        when(service.changeStatus(BOOK_ID, bookCopyId, BookCopyStatus.LENT))
                .thenThrow(new ConflictException("A copy only becomes LENT when it's lent to a member"));

        mockMvc.perform(patch("/api/v1/books/{bookId}/copies/{bookCopyId}", BOOK_ID, bookCopyId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"LENT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A copy only becomes LENT when it's lent to a member"));
    }

    @Test
    void deletingACopyWithdrawsIt() throws Exception {
        UUID bookCopyId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/books/{bookId}/copies/{bookCopyId}", BOOK_ID, bookCopyId))
                .andExpect(status().isNoContent());

        verify(service).withdraw(BOOK_ID, bookCopyId);
    }

    @Test
    void answersDeletingAnUnknownCopyWithNotFound() throws Exception {
        UUID bookCopyId = UUID.randomUUID();
        doThrow(new NotFoundException("Book " + BOOK_ID + " has no copy with id " + bookCopyId))
                .when(service).withdraw(BOOK_ID, bookCopyId);

        mockMvc.perform(delete("/api/v1/books/{bookId}/copies/{bookCopyId}", BOOK_ID, bookCopyId))
                .andExpect(status().isNotFound());
    }

    private static BookCopyDetails copyWithStatus(BookCopyStatus status) {
        return new BookCopyDetails(UUID.randomUUID(), BOOK_ID, "LIB-000123", status,
                Instant.parse("2026-06-15T10:00:00Z"));
    }
}
