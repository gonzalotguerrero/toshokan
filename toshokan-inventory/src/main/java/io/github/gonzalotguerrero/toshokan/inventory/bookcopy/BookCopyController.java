package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @GetMapping
    public List<BookCopyDetails> listBookCopies(@PathVariable("bookId") UUID bookId) {
        return service.listBookCopies(bookId);
    }

    @PatchMapping("/{bookCopyId}")
    public BookCopyDetails changeStatus(@PathVariable("bookId") UUID bookId,
                                        @PathVariable("bookCopyId") UUID bookCopyId,
                                        @Valid @RequestBody ChangeBookCopyStatusRequest request) {
        return service.changeStatus(bookId, bookCopyId, request.status());
    }

    /** Doesn't erase anything: the copy is marked as withdrawn, so there's still a record of it. */
    @DeleteMapping("/{bookCopyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable("bookId") UUID bookId, @PathVariable("bookCopyId") UUID bookCopyId) {
        service.withdraw(bookId, bookCopyId);
    }
}
