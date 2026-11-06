package io.github.gonzalotguerrero.toshokan.members.member;

import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberService service;

    public MemberController(MemberService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MemberDetails> createMember(@Valid @RequestBody CreateMemberRequest request) {
        MemberDetails member = service.createMember(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{memberId}")
                .buildAndExpand(member.memberId())
                .toUri();
        return ResponseEntity.created(location).body(member);
    }

    @GetMapping("/{memberId}")
    public MemberDetails getMember(@PathVariable("memberId") UUID memberId) {
        return service.getMember(memberId);
    }

    /** Everyone sorted by name, or just the owner of {@code cardNumber} when it's given. */
    @GetMapping
    public PageResponse<MemberDetails> listMembers(
            @RequestParam(name = "cardNumber", required = false) String cardNumber,
            @RequestParam(name = "pageSize", defaultValue = "" + MemberService.DEFAULT_PAGE_SIZE) int pageSize,
            @RequestParam(name = "pageToken", required = false) String pageToken) {
        return service.listMembers(cardNumber, pageSize, pageToken);
    }

    @PatchMapping("/{memberId}")
    public MemberDetails updateMember(@PathVariable("memberId") UUID memberId,
                                      @RequestBody UpdateMemberRequest request) {
        return service.updateMember(memberId, request);
    }

    /** Deactivates the member rather than erasing them, so their history stays intact. */
    @DeleteMapping("/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateMember(@PathVariable("memberId") UUID memberId) {
        service.deactivateMember(memberId);
    }
}
