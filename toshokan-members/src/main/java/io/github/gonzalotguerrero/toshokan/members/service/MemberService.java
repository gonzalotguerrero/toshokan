package io.github.gonzalotguerrero.toshokan.members.service;

import io.github.gonzalotguerrero.toshokan.members.dto.CreateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberLendingState;
import io.github.gonzalotguerrero.toshokan.members.dto.UpdateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberDirectoryEntry;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import io.github.gonzalotguerrero.toshokan.members.repository.CardNumberClaims;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberByCardNumberRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberDirectoryRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberWriter;
import io.github.gonzalotguerrero.toshokan.shared.cassandra.StaleClaims;
import io.github.gonzalotguerrero.toshokan.shared.cassandra.WriteOutcomes;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MemberService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    // Deliberately loose: something@something.something. Whether the address really exists is the library's problem
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final MemberRepository members;
    private final MemberByCardNumberRepository cardNumbers;
    private final CardNumberClaims cardNumberClaims;
    private final MemberDirectoryRepository directory;
    private final MemberWriter writer;
    private final MemberDeactivationGuard deactivationGuard;
    private final Clock clock;

    public MemberService(MemberRepository members, MemberByCardNumberRepository cardNumbers,
                         CardNumberClaims cardNumberClaims, MemberDirectoryRepository directory,
                         MemberWriter writer, MemberDeactivationGuard deactivationGuard, Clock clock) {
        this.members = members;
        this.cardNumbers = cardNumbers;
        this.cardNumberClaims = cardNumberClaims;
        this.directory = directory;
        this.writer = writer;
        this.deactivationGuard = deactivationGuard;
        this.clock = clock;
    }

    /** Signs up a new member. They start out ACTIVE, so they can borrow right away. */
    public MemberDetails createMember(CreateMemberRequest request) {
        String cardNumber = request.cardNumber().strip();
        String email = normalizeEmail(request.email());
        String phone = blankToNull(request.phone());
        requireContactDetails(email, phone);
        Member member = Member.newMember(UUID.randomUUID(), cardNumber, normalizeName(request.fullName()), email,
                phone, clock.instant().truncatedTo(ChronoUnit.MILLIS));

        if (!claimCardNumber(cardNumber, member.memberId())) {
            throw new ConflictException("Card number " + cardNumber + " is already in use");
        }
        try {
            writer.addToDirectory(member);
        } catch (RuntimeException e) {
            // The member itself was never written, so the card number can go back whatever happened here
            cardNumberClaims.release(cardNumber, member.memberId());
            throw e;
        }
        boolean inserted;
        try {
            inserted = writer.insert(member);
        } catch (RuntimeException e) {
            // If the member might still get saved (a timeout, say), the card number has to stay theirs. Should
            // they never arrive, the claim goes stale and the next member with this card takes it over
            if (WriteOutcomes.definitelyNotApplied(e)) {
                cardNumberClaims.release(cardNumber, member.memberId());
            }
            throw e;
        }
        if (!inserted) {
            cardNumberClaims.release(cardNumber, member.memberId());
            throw new IllegalStateException("A member with id " + member.memberId() + " already exists");
        }
        log.info("Created member {} with card number {}", member.memberId(), cardNumber);
        return MemberDetails.from(member);
    }

    public MemberDetails getMember(UUID memberId) {
        return MemberDetails.from(requireMember(memberId));
    }

    /** Used by other modules, e.g. to check that a member exists and is active before lending them a book. */
    public Optional<MemberDetails> findMember(UUID memberId) {
        return members.findById(memberId).map(MemberDetails::from);
    }

    /** Used by circulation to find the member behind the card they hand over at the desk. */
    public Optional<MemberDetails> findMemberByCardNumber(String cardNumber) {
        return cardNumbers.findById(cardNumber.strip()).flatMap(byCard -> findMember(byCard.memberId()));
    }

    /**
     * Lists members sorted by name, one page at a time. With a card number, it looks that one member up instead
     * and the page has either one item or none.
     *
     * <p>The order comes from the directory, but each entry is checked against the member itself and the details
     * shown come from there too (see {@link MemberDirectoryEntry}). Entries that no longer match are skipped, so a
     * page can have fewer items than asked for even when more pages follow.
     *
     * @param cardNumber {@code null} to list everyone
     * @param pageSize   between 1 and {@value #MAX_PAGE_SIZE}
     * @param pageToken  {@code null} for the first page, then the {@code nextPageToken} from the page before
     */
    public PageResponse<MemberDetails> listMembers(String cardNumber, int pageSize, String pageToken) {
        if (cardNumber != null && !cardNumber.isBlank()) {
            log.debug("Looking up member by card number {}", cardNumber.strip());
            return new PageResponse<>(findMemberByCardNumber(cardNumber).stream().toList(), null);
        }

        log.debug("Listing members (pageSize={}, firstPage={})", pageSize, pageToken == null);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        ByteBuffer pagingState = PageTokens.decode(pageToken);
        CassandraPageRequest pageRequest = CassandraPageRequest.of(PageRequest.of(0, pageSize), pagingState);
        Slice<MemberDirectoryEntry> slice;
        try {
            slice = directory.findByDirectoryPartition(MemberDirectoryEntry.DIRECTORY_PARTITION, pageRequest);
        } catch (DataAccessException e) {
            if (pagingState != null) {
                // A tampered token, or one from a different query, makes Cassandra fail
                throw new InvalidRequestException("Invalid page token", e);
            }
            throw e;
        }
        String nextPageToken = slice.hasNext()
                ? PageTokens.encode(((CassandraPageRequest) slice.nextPageable()).getPagingState())
                : null;
        List<MemberDirectoryEntry> entries = slice.getContent();
        List<UUID> memberIds = entries.stream().map(MemberDirectoryEntry::memberId).toList();
        Map<UUID, Member> current = members.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::memberId, Function.identity()));
        List<MemberDetails> page = entries.stream()
                .map(entry -> Optional.ofNullable(current.get(entry.memberId())).filter(entry::isCurrentFor))
                .flatMap(Optional::stream)
                .map(MemberDetails::from)
                .toList();
        return new PageResponse<>(page, nextPageToken);
    }

    /**
     * Changes whatever the request includes and leaves the rest alone. Setting the status to ACTIVE is how a
     * deactivated member gets reactivated.
     */
    public MemberDetails updateMember(UUID memberId, UpdateMemberRequest request) {
        Member current = requireMember(memberId);
        String fullName = request.fullName() == null ? current.fullName() : normalizeName(request.fullName());
        String email = request.email() == null ? current.email() : normalizeEmail(request.email());
        String phone = request.phone() == null ? current.phone() : blankToNull(request.phone());
        requireContactDetails(email, phone);
        MemberStatus status = request.status() == null ? current.status() : request.status();

        Member updated = current.withContactDetails(fullName, email, phone).withStatus(status);
        if (updated.equals(current)) {
            return MemberDetails.from(current);
        }
        if (current.status() == MemberStatus.ACTIVE && status == MemberStatus.INACTIVE) {
            // Setting the status to INACTIVE is a deactivation too, so it gets the same loan checks as DELETE
            deactivationGuard.checkCanDeactivate(MemberLendingState.from(current));
        }
        save(current, updated);
        log.info("Updated member {}", memberId);
        return MemberDetails.from(updated);
    }

    /**
     * This is what deleting a member means here: they're marked INACTIVE and can't borrow anymore, but the record
     * stays. It's refused while they still have books out. Deactivating someone who's already inactive does
     * nothing.
     */
    public void deactivateMember(UUID memberId) {
        Member current = requireMember(memberId);
        if (current.status() == MemberStatus.INACTIVE) {
            return;
        }
        Member updated = current.withStatus(MemberStatus.INACTIVE);
        deactivationGuard.checkCanDeactivate(MemberLendingState.from(current));
        save(current, updated);
        log.info("Deactivated member {}", memberId);
    }

    /** Used by circulation to check a lend or a deactivation against the member as they are now. */
    public MemberLendingState lendingState(UUID memberId) {
        return MemberLendingState.from(requireMember(memberId));
    }

    /**
     * Reserves a slot for a new loan, as long as the member hasn't changed since {@code seen} was read. The slots
     * become {@code keptSlots} plus the new one, which is how slots that no longer matter get dropped. Returns
     * {@code false} if the member changed, in which case nothing was written.
     */
    public boolean reserveLoanSlot(MemberLendingState seen, Map<UUID, Instant> keptSlots, UUID loanId,
                                   Instant reservedAt) {
        Map<UUID, Instant> slots = new HashMap<>(keptSlots);
        slots.put(loanId, reservedAt);
        return writer.replaceLoanSlots(seen.memberId(), seen.version(), slots);
    }

    /** Gives a slot back after a lend that certainly didn't happen, so it doesn't count against the member. */
    public void releaseLoanSlot(UUID memberId, UUID loanId) {
        writer.removeLoanSlot(memberId, loanId);
    }

    /**
     * Writes a change to a member that was read as {@code current}. If anyone changed the member in between, the
     * write doesn't happen and the request gets a conflict, so no change is ever silently lost. A rename adds the
     * new directory entry first; if the change then loses the race, that entry is simply skipped when listing.
     */
    private void save(Member current, Member updated) {
        if (!MemberDirectoryEntry.of(current).equals(MemberDirectoryEntry.of(updated))) {
            writer.addToDirectory(updated);
        }
        if (!writer.update(current, updated)) {
            throw new ConflictException("Member " + current.cardNumber()
                    + " was changed by someone else at the same time. Try again");
        }
    }

    /**
     * Claims a card number for a new member. If another member holds it, the claim is only taken over when it's
     * stale (see {@link StaleClaims}), which means a sign-up that failed long enough ago.
     */
    private boolean claimCardNumber(String cardNumber, UUID memberId) {
        if (cardNumberClaims.claim(cardNumber, memberId)) {
            return true;
        }
        Optional<CardNumberClaims.Claim> claim = cardNumberClaims.currentClaim(cardNumber);
        if (claim.isEmpty()) {
            // Freed between our claim and this read, so it's worth one more try
            return cardNumberClaims.claim(cardNumber, memberId);
        }
        UUID holder = claim.get().memberId();
        if (StaleClaims.isStale(claim.get().claimedAt(), members.existsById(holder), clock)
                && cardNumberClaims.takeOver(cardNumber, holder, memberId)) {
            log.info("Took over card number {} from member {}, who doesn't exist", cardNumber, holder);
            return true;
        }
        return false;
    }

    private Member requireMember(UUID memberId) {
        return members.findById(memberId).orElseThrow(() -> new NotFoundException("No member with id " + memberId));
    }

    private static void requireContactDetails(String email, String phone) {
        if (email == null && phone == null) {
            throw new InvalidRequestException("A member needs an email or a phone number");
        }
    }

    /** Trims the name and squeezes repeated spaces, so "Ana   Pérez " is stored as "Ana Pérez". */
    private static String normalizeName(String fullName) {
        String name = WHITESPACE.matcher(fullName.strip()).replaceAll(" ");
        if (name.isEmpty()) {
            throw new InvalidRequestException("fullName can't be blank");
        }
        return name;
    }

    /** Lower-cases the address, since nobody means two different people by "Ana@x.com" and "ana@x.com". */
    private static String normalizeEmail(String email) {
        String value = blankToNull(email);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(value).matches()) {
            throw new InvalidRequestException("email doesn't look like an email address");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
