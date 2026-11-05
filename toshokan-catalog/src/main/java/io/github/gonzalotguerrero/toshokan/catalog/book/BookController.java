package io.github.gonzalotguerrero.toshokan.catalog.book;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/** Creating and fetching books*/
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
}
