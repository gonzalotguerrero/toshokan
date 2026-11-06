package io.github.gonzalotguerrero.toshokan.members.service;

import io.github.gonzalotguerrero.toshokan.members.dto.CreateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.dto.UpdateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberByCardNumber;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.CassandraInsufficientReplicasAvailableException;
import org.springframework.data.cassandra.CassandraWriteTimeoutException;
import org.springframework.data.domain.SliceImpl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private static final Member ANA = new Member(UUID.randomUUID(), "CARD-0001", "Ana Pérez", "ana@example.com",
            null, MemberStatus.ACTIVE, Instant.parse("2026-01-10T09:00:00Z"), 3L);

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

    @Mock
    private MemberDeactivationGuard deactivationGuard;

    private MemberService service;

    @BeforeEach
    void setUp() {
        service = new MemberService(members, cardNumbers, cardNumberClaims, directory, writer, deactivationGuard,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAnActiveMemberAndAddsThemToTheDirectoryFirst() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);
        when(writer.insert(any())).thenReturn(true);

        MemberDetails created = service.createMember(
                new CreateMemberRequest(" CARD-0001 ", "  Ana   Pérez ", " Ana@Example.COM ", "  "));

        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        InOrder order = inOrder(writer);
        order.verify(writer).addToDirectory(any());
        order.verify(writer).insert(saved.capture());
        assertThat(saved.getValue().cardNumber()).isEqualTo("CARD-0001");
        assertThat(saved.getValue().fullName()).isEqualTo("Ana Pérez");
        assertThat(saved.getValue().email()).isEqualTo("ana@example.com");
        assertThat(saved.getValue().phone()).isNull();
        assertThat(saved.getValue().status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(saved.getValue().version()).isEqualTo(1L);
        assertThat(created).isEqualTo(MemberDetails.from(saved.getValue()));
        verify(cardNumberClaims).claim("CARD-0001", created.memberId());
    }

    @Test
    void acceptsAPhoneInsteadOfAnEmail() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);
        when(writer.insert(any())).thenReturn(true);

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
    void refusesACardNumberAnotherMemberHas() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(false);
        when(cardNumberClaims.currentClaim("CARD-0001"))
                .thenReturn(Optional.of(new CardNumberClaims.Claim(ANA.memberId(), NOW.minus(Duration.ofDays(90)))));
        when(members.existsById(ANA.memberId())).thenReturn(true);

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Card number CARD-0001 is already in use");
        verifyNoInteractions(writer);
        verify(cardNumberClaims, never()).takeOver(any(), any(), any());
    }

    @Test
    void takesOverAnOldCardClaimWhoseMemberDoesNotExist() {
        UUID gone = UUID.randomUUID();
        when(cardNumberClaims.claim(eq("CARD-0001"), any())).thenReturn(false);
        when(cardNumberClaims.currentClaim("CARD-0001"))
                .thenReturn(Optional.of(new CardNumberClaims.Claim(gone, NOW.minus(Duration.ofHours(1)))));
        when(members.existsById(gone)).thenReturn(false);
        when(cardNumberClaims.takeOver(eq("CARD-0001"), eq(gone), any())).thenReturn(true);
        when(writer.insert(any())).thenReturn(true);

        MemberDetails created = service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null));

        verify(cardNumberClaims).takeOver("CARD-0001", gone, created.memberId());
    }

    @Test
    void leavesAYoungCardClaimAloneEvenIfItsMemberIsNotThereYet() {
        // Another sign-up may have claimed it a moment ago and be about to save its member
        when(cardNumberClaims.claim(eq("CARD-0001"), any())).thenReturn(false);
        when(cardNumberClaims.currentClaim("CARD-0001"))
                .thenReturn(Optional.of(new CardNumberClaims.Claim(UUID.randomUUID(), NOW.minusSeconds(5))));

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(ConflictException.class);
        verify(cardNumberClaims, never()).takeOver(any(), any(), any());
    }

    @Test
    void givesTheCardNumberBackIfTheMemberWasCertainlyNotSaved() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new CassandraInsufficientReplicasAvailableException(1, 0, "unavailable", null))
                .when(writer).insert(any());

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(CassandraInsufficientReplicasAvailableException.class);
        verify(cardNumberClaims).release(eq("CARD-0001"), any());
    }

    @Test
    void keepsTheCardNumberIfTheMemberMightStillBeSaved() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new CassandraWriteTimeoutException("CAS", "timed out", null)).when(writer).insert(any());

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(CassandraWriteTimeoutException.class);
        // If the member lands after all, the number is theirs. If not, the claim goes stale and gets taken over
        verify(cardNumberClaims, never()).release(anyString(), any());
    }

    @Test
    void givesTheCardNumberBackIfTheDirectoryEntryFails() {
        when(cardNumberClaims.claim(anyString(), any())).thenReturn(true);
        doThrow(new CassandraWriteTimeoutException("SIMPLE", "timed out", null)).when(writer).addToDirectory(any());

        assertThatThrownBy(() -> service.createMember(
                new CreateMemberRequest("CARD-0001", "Ana", "ana@example.com", null)))
                .isInstanceOf(CassandraWriteTimeoutException.class);
        // The member was never written, so whatever happened to the entry, the number can go back
        verify(cardNumberClaims).release(eq("CARD-0001"), any());
        verify(writer, never()).insert(any());
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
    void theListOnlyShowsEntriesThatMatchTheMemberAsTheyAreNow() {
        Member renamed = ANA.withContactDetails("Ana Gómez", ANA.email(), null);
        Member bruno = new Member(UUID.randomUUID(), "CARD-0002", "Bruno Díaz", null, "555-0000",
                MemberStatus.ACTIVE, NOW, 1L);
        UUID neverSaved = UUID.randomUUID();
        when(directory.findByDirectoryPartition(eq(MemberDirectoryEntry.DIRECTORY_PARTITION), any()))
                .thenReturn(new SliceImpl<>(List.of(
                        new MemberDirectoryEntry(0, "ana gómez", ANA.memberId()),
                        new MemberDirectoryEntry(0, "ana pérez", ANA.memberId()),
                        new MemberDirectoryEntry(0, "bruno díaz", bruno.memberId()),
                        new MemberDirectoryEntry(0, "carla", neverSaved))));
        when(members.findAllById(List.of(ANA.memberId(), ANA.memberId(), bruno.memberId(), neverSaved)))
                .thenReturn(List.of(renamed, bruno));

        List<MemberDetails> page = service.listMembers(null, 20, null).items();

        // The old name's entry and the entry of a member that was never saved are skipped
        assertThat(page).containsExactly(MemberDetails.from(renamed), MemberDetails.from(bruno));
    }

    @Test
    void updatesOnlyTheFieldsThatWereSent() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));
        when(writer.update(any(), any())).thenReturn(true);

        MemberDetails updated = service.updateMember(ANA.memberId(),
                new UpdateMemberRequest(null, null, "555-1234", null));

        assertThat(updated.fullName()).isEqualTo("Ana Pérez");
        assertThat(updated.email()).isEqualTo("ana@example.com");
        assertThat(updated.phone()).isEqualTo("555-1234");
        verify(writer).update(ANA, ANA.withContactDetails("Ana Pérez", "ana@example.com", "555-1234"));
        // Same name, same place in the directory
        verify(writer, never()).addToDirectory(any());
    }

    @Test
    void anUpdateThatLostTheRaceIsAConflict() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));
        when(writer.update(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.updateMember(ANA.memberId(),
                new UpdateMemberRequest(null, null, "555-1234", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Member CARD-0001 was changed by someone else at the same time. Try again");
    }

    @Test
    void anEmptyStringClearsTheEmail() {
        Member withPhone = ANA.withContactDetails("Ana Pérez", "ana@example.com", "555-1234");
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(withPhone));
        when(writer.update(any(), any())).thenReturn(true);

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
    void aRenameAddsTheNewDirectoryEntryBeforeChangingTheMember() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));
        when(writer.update(any(), any())).thenReturn(true);

        service.updateMember(ANA.memberId(), new UpdateMemberRequest("Ana Gómez", null, null, null));

        Member renamed = ANA.withContactDetails("Ana Gómez", ANA.email(), null);
        InOrder order = inOrder(writer);
        order.verify(writer).addToDirectory(renamed);
        order.verify(writer).update(ANA, renamed);
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
        when(writer.update(any(), any())).thenReturn(true);

        MemberDetails updated = service.updateMember(ANA.memberId(),
                new UpdateMemberRequest(null, null, null, MemberStatus.ACTIVE));

        assertThat(updated.status()).isEqualTo(MemberStatus.ACTIVE);
        verify(writer).update(ANA.withStatus(MemberStatus.INACTIVE), ANA);
    }

    @Test
    void deactivatingMarksTheMemberAsInactive() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));
        when(writer.update(any(), any())).thenReturn(true);
        guardAllowsDeactivation();

        service.deactivateMember(ANA.memberId());

        verify(writer).update(ANA, ANA.withStatus(MemberStatus.INACTIVE));
    }

    @Test
    void aDeactivationThatLostTheRaceIsAConflict() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));
        when(writer.update(any(), any())).thenReturn(false);
        guardAllowsDeactivation();

        assertThatThrownBy(() -> service.deactivateMember(ANA.memberId())).isInstanceOf(ConflictException.class);
    }

    @Test
    void doesNotDeactivateAMemberTheGuardRefuses() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));
        doThrow(new ConflictException("Member still has 1 book out"))
                .when(deactivationGuard).runGuarded(eq(ANA.memberId()), any());

        assertThatThrownBy(() -> service.deactivateMember(ANA.memberId())).isInstanceOf(ConflictException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void settingTheStatusToInactiveGoesThroughTheGuardToo() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));
        when(writer.update(any(), any())).thenReturn(true);
        guardAllowsDeactivation();

        service.updateMember(ANA.memberId(), new UpdateMemberRequest(null, null, null, MemberStatus.INACTIVE));

        verify(deactivationGuard).runGuarded(eq(ANA.memberId()), any());
        verify(writer).update(ANA, ANA.withStatus(MemberStatus.INACTIVE));
    }

    @Test
    void contactChangesDoNotNeedTheGuard() {
        when(members.findById(ANA.memberId())).thenReturn(Optional.of(ANA));
        when(writer.update(any(), any())).thenReturn(true);

        service.updateMember(ANA.memberId(), new UpdateMemberRequest(null, null, "555-1234", null));

        verifyNoInteractions(deactivationGuard);
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

    /** Makes the guard behave like it does when the member has no books out: it just runs the deactivation. */
    private void guardAllowsDeactivation() {
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(deactivationGuard).runGuarded(eq(ANA.memberId()), any());
    }
}
