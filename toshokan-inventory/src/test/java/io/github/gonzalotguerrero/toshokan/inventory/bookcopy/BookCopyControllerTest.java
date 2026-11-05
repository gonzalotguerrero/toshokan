package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
}
