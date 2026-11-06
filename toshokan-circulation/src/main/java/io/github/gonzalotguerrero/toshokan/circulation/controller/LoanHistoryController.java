package io.github.gonzalotguerrero.toshokan.circulation.controller;

import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanHistoryEntry;
import io.github.gonzalotguerrero.toshokan.circulation.service.LoanHistoryService;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Past and current loans, for one member or one copy, newest first. */
@RestController
@RequestMapping("/api/v1")
public class LoanHistoryController {

    private final LoanHistoryService service;

    public LoanHistoryController(LoanHistoryService service) {
        this.service = service;
    }

    @GetMapping("/members/{memberId}/loan-history")
    public PageResponse<LoanHistoryEntry> memberHistory(
            @PathVariable("memberId") UUID memberId,
            @RequestParam(name = "pageSize", defaultValue = "" + LoanHistoryService.DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(name = "pageToken", required = false) String pageToken) {
        return service.memberHistory(memberId, pageSize, pageToken);
    }

    @GetMapping("/books/{bookId}/copies/{bookCopyId}/loan-history")
    public PageResponse<LoanHistoryEntry> copyHistory(
            @PathVariable("bookId") UUID bookId,
            @PathVariable("bookCopyId") UUID bookCopyId,
            @RequestParam(name = "pageSize", defaultValue = "" + LoanHistoryService.DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(name = "pageToken", required = false) String pageToken) {
        return service.copyHistory(bookId, bookCopyId, pageSize, pageToken);
    }
}
