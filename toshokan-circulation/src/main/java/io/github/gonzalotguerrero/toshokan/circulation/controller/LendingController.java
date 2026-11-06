package io.github.gonzalotguerrero.toshokan.circulation.controller;

import io.github.gonzalotguerrero.toshokan.circulation.dto.LendRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanDetails;
import io.github.gonzalotguerrero.toshokan.circulation.dto.OverdueLoan;
import io.github.gonzalotguerrero.toshokan.circulation.dto.RenewalRequest;
import io.github.gonzalotguerrero.toshokan.circulation.dto.ReturnRequest;
import io.github.gonzalotguerrero.toshokan.circulation.service.LendingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The lending desk. Books and members are identified by the numbers the librarian scans: the label on the book
 * and the member's card.
 */
@RestController
@RequestMapping("/api/v1")
public class LendingController {

    private final LendingService service;

    public LendingController(LendingService service) {
        this.service = service;
    }

    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanDetails lend(@Valid @RequestBody LendRequest request) {
        return service.lend(request);
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void returnCopy(@Valid @RequestBody ReturnRequest request) {
        service.returnCopy(request);
    }

    @PostMapping("/renewals")
    public LoanDetails renew(@Valid @RequestBody RenewalRequest request) {
        return service.renew(request);
    }

    @GetMapping("/members/{memberId}/loans")
    public List<LoanDetails> memberLoans(@PathVariable("memberId") UUID memberId) {
        return service.memberLoans(memberId);
    }

    @GetMapping("/loans/overdue")
    public List<OverdueLoan> overdueLoans() {
        return service.overdueLoans();
    }
}
