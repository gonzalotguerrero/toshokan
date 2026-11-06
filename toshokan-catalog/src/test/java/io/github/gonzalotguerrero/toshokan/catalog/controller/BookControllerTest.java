package io.github.gonzalotguerrero.toshokan.catalog.controller;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.dto.CreateBookRequest;
import io.github.gonzalotguerrero.toshokan.catalog.service.BookService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
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
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookControllerTest {

    private static final String VALID_BODY = """
            {"isbn": "9780441013593", "title": "Dune", "authors": ["Frank Herbert"], "publisher": "Ace",
             "publicationYear": 1965, "language": "English", "genres": ["Science fiction"]}
            """;

    private static final BookDetails DUNE = new BookDetails(UUID.randomUUID(), "9780441013593", "Dune",
            List.of("Frank Herbert"), "Ace", 1965, "English", Set.of("Science fiction"),
            Instant.parse("2026-06-15T10:00:00Z"));

    @Mock
    private BookService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BookController(service))
                .setControllerAdvice(new ApiExceptionHandlerAdvice())
                .build();
    }

    @Test
    void createsABookAndPointsToIt() throws Exception {
        when(service.createBook(any())).thenReturn(DUNE);

        mockMvc.perform(post("/api/v1/books").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/books/" + DUNE.bookId()))
                .andExpect(jsonPath("$.bookId").value(DUNE.bookId().toString()))
                .andExpect(jsonPath("$.title").value("Dune"))
                .andExpect(jsonPath("$.genres[0]").value("Science fiction"));

        verify(service).createBook(new CreateBookRequest("9780441013593", "Dune", List.of("Frank Herbert"), "Ace",
                1965, "English", List.of("Science fiction")));
    }

    @Test
    void acceptsABookWithoutTheOptionalFields() throws Exception {
        when(service.createBook(any())).thenReturn(DUNE);

        mockMvc.perform(post("/api/v1/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "Dune", "authors": ["Frank Herbert"], "publicationYear": 1965,
                         "language": "English", "genres": ["Science fiction"]}
                        """))
                .andExpect(status().isCreated());
    }

    @Test
    void listsEveryMissingRequiredField() throws Exception {
        mockMvc.perform(post("/api/v1/books").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.authors").exists())
                .andExpect(jsonPath("$.errors.publicationYear").exists())
                .andExpect(jsonPath("$.errors.language").exists())
                .andExpect(jsonPath("$.errors.genres").exists())
                .andExpect(jsonPath("$.errors.isbn").doesNotExist())
                .andExpect(jsonPath("$.errors.publisher").doesNotExist());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsEmptyListsAndBlankEntries() throws Exception {
        mockMvc.perform(post("/api/v1/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": " ", "authors": [], "publicationYear": 1965,
                         "language": "English", "genres": ["Horror", "  "]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.authors").exists())
                .andExpect(jsonPath("$.errors['genres[1]']").exists());

        verifyNoInteractions(service);
    }

    @Test
    void answersADuplicateIsbnWithConflict() throws Exception {
        when(service.createBook(any())).thenThrow(new ConflictException("A book with ISBN 9780441013593 already exists"));

        mockMvc.perform(post("/api/v1/books").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A book with ISBN 9780441013593 already exists"));
    }

    @Test
    void returnsABook() throws Exception {
        when(service.getBook(DUNE.bookId())).thenReturn(DUNE);

        mockMvc.perform(get("/api/v1/books/{bookId}", DUNE.bookId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value("9780441013593"))
                .andExpect(jsonPath("$.authors[0]").value("Frank Herbert"))
                .andExpect(jsonPath("$.publicationYear").value(1965));
    }

    @Test
    void answersAnUnknownBookWithNotFound() throws Exception {
        UUID bookId = UUID.randomUUID();
        when(service.getBook(bookId)).thenThrow(new NotFoundException("No book with id " + bookId));

        mockMvc.perform(get("/api/v1/books/{bookId}", bookId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No book with id " + bookId));
    }

    @Test
    void rejectsABookIdThatIsNotAUuid() throws Exception {
        mockMvc.perform(get("/api/v1/books/{bookId}", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void searchesBooksByAuthor() throws Exception {
        when(service.searchBooks(null, "Frank Herbert", null, 20, null))
                .thenReturn(new PageResponse<>(List.of(DUNE), "next"));

        mockMvc.perform(get("/api/v1/books/search").param("author", "Frank Herbert"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Dune"))
                .andExpect(jsonPath("$.nextPageToken").value("next"));
    }

    @Test
    void passesThePagingThroughWhenSearchingByTitle() throws Exception {
        when(service.searchBooks(null, null, "Dune", 5, "abc")).thenReturn(new PageResponse<>(List.of(DUNE), null));

        mockMvc.perform(get("/api/v1/books/search").param("title", "Dune").param("pageSize", "5")
                        .param("pageToken", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bookId").value(DUNE.bookId().toString()));
    }

    @Test
    void answersASearchWithoutCriteriaWithBadRequest() throws Exception {
        when(service.searchBooks(null, null, null, 20, null))
                .thenThrow(new InvalidRequestException("Search by exactly one of isbn, author or title"));

        mockMvc.perform(get("/api/v1/books/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Search by exactly one of isbn, author or title"));
    }

    @Test
    void deletesABook() throws Exception {
        mockMvc.perform(delete("/api/v1/books/{bookId}", DUNE.bookId()))
                .andExpect(status().isNoContent());

        verify(service).deleteBook(DUNE.bookId());
    }

    @Test
    void answersDeletingABookThatStillHasCopiesWithConflict() throws Exception {
        doThrow(new ConflictException("Book still has 1 copy that isn't withdrawn"))
                .when(service).deleteBook(DUNE.bookId());

        mockMvc.perform(delete("/api/v1/books/{bookId}", DUNE.bookId()))
                .andExpect(status().isConflict());
    }
}
