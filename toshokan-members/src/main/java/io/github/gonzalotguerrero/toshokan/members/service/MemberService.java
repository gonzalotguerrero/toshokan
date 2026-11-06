package io.github.gonzalotguerrero.toshokan.members.service;

import io.github.gonzalotguerrero.toshokan.members.dto.CreateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.dto.UpdateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberDirectoryEntry;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import io.github.gonzalotguerrero.toshokan.members.repository.CardNumberClaims;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberByCardNumberRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberDirectoryRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberWriter;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.cassandra.CassandraWriteTimeoutException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MemberService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    // The write type Cassandra reports when a logged batch timed out after it was safely recorded
    private static final String BATCH_ALREADY_LOGGED = "BATCH";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    // Deliberately loose: something@something.something. Whether the address really exists is the library's problem
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final MemberRepository members;
    private final MemberByCardNumberRepository cardNumbers;
    private final CardNumberClaims cardNumberClaims;
    private final MemberDirectoryRepository directory;
    private final MemberWriter writer;

    public MemberService(MemberRepository members, MemberByCardNumberRepository cardNumbers,
                         CardNumberClaims cardNumberClaims, MemberDirectoryRepository directory,
                         MemberWriter writer) {
        this.members = members;
        this.cardNumbers = cardNumbers;
        this.cardNumberClaims = cardNumberClaims;
        this.directory = directory;
        this.writer = writer;
    }

    /** Signs up a new member. They start out ACTIVE, so they can borrow right away. */
    public MemberDetails createMember(CreateMemberRequest request) {
        String cardNumber = request.cardNumber().strip();
        String email = normalizeEmail(request.email());
        String phone = blankToNull(request.phone());
        requireContactDetails(email, phone);
        Member member = new Member(UUID.randomUUID(), cardNumber, normalizeName(request.fullName()), email, phone,
                MemberStatus.ACTIVE, Instant.now().truncatedTo(ChronoUnit.MILLIS));

        if (!cardNumberClaims.claim(cardNumber, member.memberId())) {
            throw new ConflictException("Card number " + cardNumber + " is already in use");
        }
        try {
            writer.save(null, member);
        } catch (CassandraWriteTimeoutException e) {
            if (!BATCH_ALREADY_LOGGED.equals(e.getWriteType())) {
                cardNumberClaims.release(cardNumber, member.memberId());
            }
            // Otherwise Cassandra already has the batch and will finish applying it, so the member is coming and
            // the card number has to stay theirs
            throw e;
        } catch (RuntimeException e) {
            cardNumberClaims.release(cardNumber, member.memberId());
            throw e;
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

    /**
     * Lists members sorted by name, one page at a time. With a card number, it looks that one member up instead
     * and the page has either one item or none.
     *
     * @param cardNumber {@code null} to list everyone
     * @param pageSize   between 1 and {@value #MAX_PAGE_SIZE}
     * @param pageToken  {@code null} for the first page, then the {@code nextPageToken} from the page before
     */
    public PageResponse<MemberDetails> listMembers(String cardNumber, int pageSize, String pageToken) {
        if (cardNumber != null && !cardNumber.isBlank()) {
            log.debug("Looking up member by card number {}", cardNumber.strip());
            List<MemberDetails> found = cardNumbers.findById(cardNumber.strip())
                    .flatMap(byCard -> findMember(byCard.memberId()))
                    .stream()
                    .toList();
            return new PageResponse<>(found, null);
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
        return new PageResponse<>(slice.map(MemberDetails::from).getContent(), nextPageToken);
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
        writer.save(current, updated);
        log.info("Updated member {}", memberId);
        return MemberDetails.from(updated);
    }

    /**
     * This is what deleting a member means here: they're marked INACTIVE and can't borrow anymore, but the record
     * stays. Deactivating someone who's already inactive does nothing.
     */
    public void deactivateMember(UUID memberId) {
        Member current = requireMember(memberId);
        if (current.status() == MemberStatus.INACTIVE) {
            return;
        }
        Member updated = current.withStatus(MemberStatus.INACTIVE);
        writer.save(current, updated);
        log.info("Deactivated member {}", memberId);
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
