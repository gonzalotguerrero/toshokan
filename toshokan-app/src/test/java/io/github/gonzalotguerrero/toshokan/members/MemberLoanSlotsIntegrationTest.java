package io.github.gonzalotguerrero.toshokan.members;

import io.github.gonzalotguerrero.toshokan.TestcontainersConfiguration;
import io.github.gonzalotguerrero.toshokan.members.dto.CreateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberLendingState;
import io.github.gonzalotguerrero.toshokan.members.dto.UpdateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.model.Member;
import io.github.gonzalotguerrero.toshokan.members.model.MemberByCardNumber;
import io.github.gonzalotguerrero.toshokan.members.model.MemberDirectoryEntry;
import io.github.gonzalotguerrero.toshokan.members.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.cassandra.core.CassandraOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the loan slot writes against a real Cassandra. The lending tests rarely get as far as giving a slot back,
 * because a lend that can't work is usually refused before its slot is reserved, so the statements are checked here.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MemberLoanSlotsIntegrationTest {

    // Cassandra keeps timestamps to the millisecond, so anything finer wouldn't read back the same
    private static final Instant RESERVED_AT = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private CassandraOperations cassandra;

    @Autowired
    private MemberService members;

    private MemberDetails ana;

    @BeforeEach
    void setUp() {
        cassandra.truncate(Member.class);
        cassandra.truncate(MemberByCardNumber.class);
        cassandra.truncate(MemberDirectoryEntry.class);
        ana = members.createMember(new CreateMemberRequest("CARD-0001", "Ana Pérez", "ana@example.com", null));
    }

    @Test
    void aReservedSlotCanBeGivenBack() {
        UUID loanId = UUID.randomUUID();
        MemberLendingState before = members.lendingState(ana.memberId());

        assertThat(members.reserveLoanSlot(before, Map.of(), loanId, RESERVED_AT)).isTrue();
        MemberLendingState reserved = members.lendingState(ana.memberId());
        assertThat(reserved.loanSlots()).isEqualTo(Map.of(loanId, RESERVED_AT));
        assertThat(reserved.version()).isEqualTo(before.version() + 1);

        assertThat(members.releaseLoanSlot(before, loanId)).isTrue();
        MemberLendingState released = members.lendingState(ana.memberId());
        assertThat(released.loanSlots()).isEmpty();
        assertThat(released.version()).isEqualTo(before.version() + 2);
    }

    @Test
    void reservingFromAnOutdatedReadWritesNothing() {
        MemberLendingState before = members.lendingState(ana.memberId());
        members.reserveLoanSlot(before, Map.of(), UUID.randomUUID(), RESERVED_AT);

        assertThat(members.reserveLoanSlot(before, Map.of(), UUID.randomUUID(), RESERVED_AT)).isFalse();
        assertThat(members.lendingState(ana.memberId()).loanSlots()).hasSize(1);
    }

    @Test
    void aSlotIsNotGivenBackOnceTheMemberChangedSinceTheReservation() {
        // Otherwise a lend that read the member in between could write the slot back without noticing
        UUID loanId = UUID.randomUUID();
        MemberLendingState before = members.lendingState(ana.memberId());
        members.reserveLoanSlot(before, Map.of(), loanId, RESERVED_AT);
        members.updateMember(ana.memberId(), new UpdateMemberRequest(null, null, "555-1234", null));

        assertThat(members.releaseLoanSlot(before, loanId)).isFalse();
        assertThat(members.lendingState(ana.memberId()).loanSlots()).containsOnlyKeys(loanId);
    }

    @Test
    void slotsWorkForMembersSavedBeforeVersionsExisted() {
        cassandra.getCqlOperations().execute("UPDATE members SET version = null WHERE member_id = ? IF EXISTS",
                ana.memberId());
        UUID loanId = UUID.randomUUID();
        MemberLendingState before = members.lendingState(ana.memberId());
        assertThat(before.version()).isNull();

        assertThat(members.reserveLoanSlot(before, Map.of(), loanId, RESERVED_AT)).isTrue();
        assertThat(members.lendingState(ana.memberId()).version()).isEqualTo(1L);
        assertThat(members.releaseLoanSlot(before, loanId)).isTrue();
        assertThat(members.lendingState(ana.memberId()).loanSlots()).isEmpty();
    }
}
