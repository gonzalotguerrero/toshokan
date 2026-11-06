package io.github.gonzalotguerrero.toshokan.members.service;

import io.github.gonzalotguerrero.toshokan.members.dto.CreateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.dto.UpdateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberByCardNumber;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import io.github.gonzalotguerrero.toshokan.members.repository.CardNumberClaims;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberByCardNumberRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberDirectoryRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberRepository;
import io.github.gonzalotguerrero.toshokan.members.repository.MemberWriter;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.CassandraWriteTimeoutException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    private static final Member ANA = new Member(UUID.randomUUID(), "CARD-0001", "Ana Pérez", "ana@example.com",
            null, MemberStatus.ACTIVE, Instant.parse("2026-01-10T09:00:00Z"));

    @Mock
    private MemberRepository members;

    @Mock
    private MemberByCardNumberRepository cardNumbers;

    @Mock
    private CardNumberClaims cardNumberClaims;

    @Mock
    private MemberDirectoryRepository directory;

    @Mock
    private MemberWriter writer;

    @InjectMocks
    private MemberService service;

    @Test
    void createsAnActiveMemberAndAddsThemToTheDirectory() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);

        MemberDetails created = service.createMember(
                new CreateMemberRequest(" CARD-0001 ", "  Ana   Pérez ", " Ana@Example.COM ", "  "));

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(writer).save(isNull(), saved.capture());
        assertThat(saved.getValue().cardNumber()).isEqualTo("CARD-0001");
        assertThat(saved.getValue().fullName()).isEqualTo("Ana Pérez");
        assertThat(saved.getValue().email()).isEqualTo("ana@example.com");
        assertThat(saved.getValue().phone()).isNull();
        assertThat(saved.getValue().status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(created).isEqualTo(MemberDetails.from(saved.getValue()));
        verify(cardNumberClaims).claim("CARD-0001", created.memberId());
    }

    @Test
    void acceptsAPhoneInsteadOfAnEmail() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);

        MemberDetails created = service.createMember(new CreateMemberRequest("CARD-0001", "Ana", null, " 555-1234 "));

        assertThat(created.email()).isNull();
        assertThat(created.phone()).isEqualTo("555-1234");
    }

    @Test
    void refusesAMemberWithNoWayToContactThem() {
        assertThatThrownBy(() -> service.createMember(new CreateMemberRequest("CARD-0001", "Ana", " ", null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("A member needs an email or a phone number");
        verifyNoInteractions(cardNumberClaims, writer);
    }

    @Test
    void refusesAnEmailThatDoesNotLookLikeOne() {
        assertThatThrownBy(() -> service.createMember(new CreateMemberRequest("CARD-0001", "Ana", "ana.example", null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("email doesn't look like an email address");
        verifyNoInteractions(cardNumberClaims, writer);
    }

    @Test
    void refusesACardNumberAlreadyInUse() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Card number CARD-0001 is already in use");
        verifyNoInteractions(writer);
    }

    @Test
    void givesTheCardNumberBackIfSavingTheMemberFails() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("Cassandra is down")).when(writer).save(isNull(), any());

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(IllegalStateException.class);
        verify(cardNumberClaims).release(eq("CARD-0001"), any());
    }

    @Test
    void givesTheCardNumberBackIfTheBatchTimedOutBeforeBeingRecorded() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new CassandraWriteTimeoutException("BATCH_LOG", "timed out", null))
                .when(writer).save(isNull(), any());

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(CassandraWriteTimeoutException.class);
        verify(cardNumberClaims).release(eq("CARD-0001"), any());
    }

    @Test
    void keepsTheCardNumberIfTheBatchWasRecordedButTimedOutAfterwards() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new CassandraWriteTimeoutException("BATCH", "timed out", null))
                .when(writer).save(isNull(), any());

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(CassandraWriteTimeoutException.class);
        // Cassandra will still apply the batch, so the member is on the way and the number has to stay theirs
        verify(cardNumberClaims, never()).release(anyString(), any());
    }

    @Test
    void failsWithNotFoundForAnUnknownMember() {
        UUID memberId = UUID.randomUUID();
        when(members.findById(memberId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMember(memberId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No member with id " + memberId);
    }

    @Test
    void findsAMemberByCardNumber() {
        when(cardNumbers.findById("CARD-0001")).thenReturn(Optional.of(new MemberByCardNumber("CARD-0001",
                ANA.memberId())));
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));

        assertThat(service.listMembers(" CARD-0001 ", 20, null).items()).containsExactly(MemberDetails.from(ANA));
        verifyNoInteractions(directory);
    }

    @Test
    void anUnknownCardNumberGivesAnEmptyPage() {
        when(cardNumbers.findById("CARD-9999")).thenReturn(Optional.empty());

        assertThat(service.listMembers("CARD-9999", 20, null).items()).isEmpty();
    }

    @Test
    void refusesAPageSizeOutOfRange() {
        assertThatThrownBy(() -> service.listMembers(null, 101, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("pageSize must be between 1 and 100");
    }

    @Test
    void updatesOnlyTheFieldsThatWereSent() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));

        MemberDetails updated = service.updateMember(ANA.memberId(),
                new UpdateMemberRequest(null, null, "555-1234", null));

        assertThat(updated.fullName()).isEqualTo("Ana Pérez");
        assertThat(updated.email()).isEqualTo("ana@example.com");
        assertThat(updated.phone()).isEqualTo("555-1234");
        verify(writer).save(ANA, ANA.withContactDetails("Ana Pérez", "ana@example.com", "555-1234"));
    }

    @Test
    void anEmptyStringClearsTheEmail() {
        Member withPhone = ANA.withContactDetails("Ana Pérez", "ana@example.com", "555-1234");
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(withPhone));

        MemberDetails updated = service.updateMember(ANA.memberId(), new UpdateMemberRequest(null, "", null, null));

        assertThat(updated.email()).isNull();
        assertThat(updated.phone()).isEqualTo("555-1234");
    }

    @Test
    void refusesToClearTheOnlyWayToContactAMember() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));

        assertThatThrownBy(() -> service.updateMember(ANA.memberId(), new UpdateMemberRequest(null, "", null, null)))
                .isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void refusesABlankName() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));

        assertThatThrownBy(() -> service.updateMember(ANA.memberId(), new UpdateMemberRequest("  ", null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("fullName can't be blank");
    }

    @Test
    void renamingAMemberHandsTheOldAndNewVersionToTheWriter() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));

        service.updateMember(ANA.memberId(), new UpdateMemberRequest("Ana Gómez", null, null, null));

        // The writer needs the old version to find and delete the old directory row
        verify(writer).save(ANA, ANA.withContactDetails("Ana Gómez", ANA.email(), null));
    }

    @Test
    void anUpdateThatChangesNothingWritesNothing() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));

        service.updateMember(ANA.memberId(), new UpdateMemberRequest("Ana  Pérez", "ANA@example.com", null, null));

        verifyNoInteractions(writer);
    }

    @Test
    void settingTheStatusToActiveReactivatesAMember() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA.withStatus(MemberStatus.INACTIVE)));

        MemberDetails updated = service.updateMember(ANA.memberId(),
                new UpdateMemberRequest(null, null, null, MemberStatus.ACTIVE));

        assertThat(updated.status()).isEqualTo(MemberStatus.ACTIVE);
        verify(writer).save(ANA.withStatus(MemberStatus.INACTIVE), ANA);
    }

    @Test
    void deactivatingMarksTheMemberAsInactive() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));

        service.deactivateMember(ANA.memberId());

        verify(writer).save(ANA, ANA.withStatus(MemberStatus.INACTIVE));
    }

    @Test
    void deactivatingAnInactiveMemberDoesNothing() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA.withStatus(MemberStatus.INACTIVE)));

        service.deactivateMember(ANA.memberId());

        verifyNoInteractions(writer);
    }

    @Test
    void deactivatingAnUnknownMemberFailsWithNotFound() {
        UUID memberId = UUID.randomUUID();
        when(members.findById(memberId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivateMember(memberId)).isInstanceOf(NotFoundException.class);
    }
}
