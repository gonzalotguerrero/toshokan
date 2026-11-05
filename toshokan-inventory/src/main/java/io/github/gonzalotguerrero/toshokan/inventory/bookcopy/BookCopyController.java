package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/books/{bookId}/copies")
public class BookCopyController {

    private final BookCopyService service;

    public BookCopyController(BookCopyService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookCopyDetails addBookCopy(@PathVariable("bookId") UUID bookId,
                                       @Valid @RequestBody AddBookCopyRequest request) {
        return service.addBookCopy(bookId, request);
    }
}
