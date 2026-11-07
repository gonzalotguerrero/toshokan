package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberLendingState;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanCheckingDeactivationGuardTest {

    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final MemberLendingState MEMBER = new MemberLendingState(MEMBER_ID, "CARD-0001",
            MemberStatus.ACTIVE, 4L, Map.of());

    @Mock
    private BookCopyService copies;

    @Mock
    private LoanSlots loanSlots;

    @InjectMocks
    private LoanCheckingDeactivationGuard guard;

    @Test
    void letsAMemberWithNoBooksOutAndNoLendInProgressBeDeactivated() {
        when(copies.findCopiesLentTo(MEMBER_ID)).thenReturn(List.of());
        when(loanSlots.pending(MEMBER, List.of())).thenReturn(Map.of());

        assertThatCode(() -> guard.checkCanDeactivate(MEMBER)).doesNotThrowAnyException();
    }

    @Test
    void refusesWhileTheMemberStillHasBooksOut() {
        when(copies.findCopiesLentTo(MEMBER_ID)).thenReturn(List.of(new BookCopyDetails(UUID.randomUUID(),
                UUID.randomUUID(), "LIB-000001", BookCopyStatus.LENT, Instant.now(), null)));

        assertThatThrownBy(() -> guard.checkCanDeactivate(MEMBER))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Member " + MEMBER_ID + " still has 1 book out. They have to be returned before the "
                        + "member can be deactivated");
        verifyNoInteractions(loanSlots);
    }

    @Test
    void refusesWhileABookIsBeingLentToTheMember() {
        when(copies.findCopiesLentTo(MEMBER_ID)).thenReturn(List.of());
        when(loanSlots.pending(MEMBER, List.of())).thenReturn(Map.of(UUID.randomUUID(), Instant.now()));

        assertThatThrownBy(() -> guard.checkCanDeactivate(MEMBER))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A book is being lent to member " + MEMBER_ID + " right now. Try again in a moment");
    }
}
