package io.github.gonzalotguerrero.toshokan.inventory.holdings;

import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/books")
public class BookHoldingsController {

    private final BookHoldingsService service;

    public BookHoldingsController(BookHoldingsService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<BookSummary> listBooks(
            @RequestParam(name = "available", defaultValue = "false") boolean available,
            @RequestParam(name = "pageSize", defaultValue = "" + BookHoldingsService.DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(name = "pageToken", required = false) String pageToken) {
        return service.listBooks(available, pageSize, pageToken);
    }
}
