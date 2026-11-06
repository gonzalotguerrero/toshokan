package io.github.gonzalotguerrero.toshokan.catalog.controller;

import io.github.gonzalotguerrero.toshokan.catalog.dto.BookDetails;
import io.github.gonzalotguerrero.toshokan.catalog.dto.CreateBookRequest;
import io.github.gonzalotguerrero.toshokan.catalog.service.BookService;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/** Creating, fetching and searching books. */
@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService service;

    public BookController(BookService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BookDetails> createBook(@Valid @RequestBody CreateBookRequest request) {
        BookDetails book = service.createBook(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{bookId}")
                .buildAndExpand(book.bookId())
                .toUri();
        return ResponseEntity.created(location).body(book);
    }

    @GetMapping("/{bookId}")
    public BookDetails getBook(@PathVariable("bookId") UUID bookId) {
        return service.getBook(bookId);
    }

    /** Takes exactly one of {@code isbn}, {@code author} or {@code title}. */
    @GetMapping("/search")
    public PageResponse<BookDetails> searchBooks(
            @RequestParam(name = "isbn", required = false) String isbn,
            @RequestParam(name = "author", required = false) String author,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "pageSize", defaultValue = "" + BookService.DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(name = "pageToken", required = false) String pageToken) {
        return service.searchBooks(isbn, author, title, pageSize, pageToken);
    }
}
