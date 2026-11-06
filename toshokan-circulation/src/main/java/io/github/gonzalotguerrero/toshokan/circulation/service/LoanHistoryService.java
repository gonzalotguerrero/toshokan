package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.circulation.dto.LoanHistoryEntry;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByCopy;
import io.github.gonzalotguerrero.toshokan.circulation.model.LoanByMember;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanByCopyRepository;
import io.github.gonzalotguerrero.toshokan.circulation.repository.LoanByMemberRepository;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyLoan;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.members.service.MemberService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageTokens;
import org.springframework.dao.DataAccessException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads loan histories, by copy or by member.
 *
 * <p>History rows are written before the copy changes (see {@link LoanByCopy}), so a row on its own doesn't prove
 * anything happened. Each one is checked against the copy, which always knows its current loan:
 * <ul>
 *     <li>If the copy's current loan is this one, the loan is active, whatever the row says. Its due date and
 *     renewals come from the copy, since renewals only change the copy.</li>
 *     <li>Otherwise, if the row has a return date, the loan was returned.</li>
 *     <li>Otherwise the row is from a lend that never went through, and it's left out.</li>
 * </ul>
 * Nothing ever has to go back and fix a row: whatever the history tables hold, this gives the right answer. Because
 * some rows can be left out, a page may have fewer items than asked for, even when there are more pages.
 */
@Service
public class LoanHistoryService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final LoanByCopyRepository byCopy;
    private final LoanByMemberRepository byMember;
    private final BookCopyService copies;
    private final MemberService members;
    private final Clock clock;

    public LoanHistoryService(LoanByCopyRepository byCopy, LoanByMemberRepository byMember, BookCopyService copies,
                              MemberService members, Clock clock) {
        this.byCopy = byCopy;
        this.byMember = byMember;
        this.copies = copies;
        this.members = members;
        this.clock = clock;
    }

    /** Every loan of one copy, newest first. */
    public PageResponse<LoanHistoryEntry> copyHistory(UUID bookId, UUID bookCopyId, int pageSize, String pageToken) {
        BookCopyDetails copy = copies.findBookCopy(bookId, bookCopyId)
                .orElseThrow(() -> new NotFoundException("Book " + bookId + " has no copy with id " + bookCopyId));
        Slice<LoanByCopy> slice = page(pageSize, pageToken, request -> byCopy.findByBookCopyId(bookCopyId, request));
        List<LoanHistoryEntry> entries = slice.getContent().stream()
                .map(row -> resolve(row.loanId(), row.bookId(), row.bookCopyId(), row.inventoryNumber(),
                        row.memberId(), row.lentAt(), row.dueDate(), row.renewals(), row.returnedAt(), copy))
                .flatMap(Optional::stream)
                .toList();
        return new PageResponse<>(entries, nextToken(slice));
    }

    /** Every loan a member has had, newest first. */
    public PageResponse<LoanHistoryEntry> memberHistory(UUID memberId, int pageSize, String pageToken) {
        members.getMember(memberId);
        Slice<LoanByMember> slice = page(pageSize, pageToken, request -> byMember.findByMemberId(memberId, request));
        // The same copy can show up many times on one page, so each one is only looked up once
        Map<UUID, Optional<BookCopyDetails>> copiesSeen = new HashMap<>();
        List<LoanHistoryEntry> entries = slice.getContent().stream()
                .map(row -> resolve(row.loanId(), row.bookId(), row.bookCopyId(), row.inventoryNumber(),
                        row.memberId(), row.lentAt(), row.dueDate(), row.renewals(), row.returnedAt(),
                        copiesSeen.computeIfAbsent(row.bookCopyId(),
                                id -> copies.findBookCopy(row.bookId(), id)).orElse(null)))
                .flatMap(Optional::stream)
                .toList();
        return new PageResponse<>(entries, nextToken(slice));
    }

    /** Applies the rules from the class comment. {@code copy} is the copy as it is now, or null if it's gone. */
    private Optional<LoanHistoryEntry> resolve(UUID loanId, UUID bookId, UUID bookCopyId, String inventoryNumber,
                                               UUID memberId, Instant lentAt, LocalDate dueDate, int renewals,
                                               Instant returnedAt, BookCopyDetails copy) {
        BookCopyLoan current = copy == null ? null : copy.loan();
        if (current != null && current.loanId().equals(loanId)) {
            return Optional.of(new LoanHistoryEntry(loanId, bookId, bookCopyId, inventoryNumber, memberId, lentAt,
                    current.dueDate(), current.renewals(), null, null, current.overdue()));
        }
        if (returnedAt != null) {
            boolean late = LocalDate.ofInstant(returnedAt, clock.getZone()).isAfter(dueDate);
            return Optional.of(new LoanHistoryEntry(loanId, bookId, bookCopyId, inventoryNumber, memberId, lentAt,
                    dueDate, renewals, returnedAt, late, false));
        }
        return Optional.empty();
    }

    private static <T> Slice<T> page(int pageSize, String pageToken,
                                     Function<CassandraPageRequest, Slice<T>> query) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        ByteBuffer pagingState = PageTokens.decode(pageToken);
        try {
            return query.apply(CassandraPageRequest.of(PageRequest.of(0, pageSize), pagingState));
        } catch (DataAccessException e) {
            if (pagingState != null) {
                // A tampered token, or one from a different query, makes Cassandra fail
                throw new InvalidRequestException("Invalid page token", e);
            }
            throw e;
        }
    }

    private static String nextToken(Slice<?> slice) {
        return slice.hasNext()
                ? PageTokens.encode(((CassandraPageRequest) slice.nextPageable()).getPagingState())
                : null;
    }
}
