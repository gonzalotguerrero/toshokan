package io.github.gonzalotguerrero.toshokan.circulation.service;

import io.github.gonzalotguerrero.toshokan.circulation.repository.MemberLendingLocks;
import io.github.gonzalotguerrero.toshokan.inventory.dto.BookCopyDetails;
import io.github.gonzalotguerrero.toshokan.inventory.model.BookCopyStatus;
import io.github.gonzalotguerrero.toshokan.inventory.service.BookCopyService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanCheckingDeactivationGuardTest {

    private static final UUID MEMBER_ID = UUID.randomUUID();

    @Mock
    private MemberLendingLocks locks;

    @Mock
    private BookCopyService copies;

    @InjectMocks
    private LoanCheckingDeactivationGuard guard;

    private final AtomicBoolean deactivated = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        when(locks.whileLocked(eq(MEMBER_ID), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
    }

    @Test
    void letsAMemberWithNoBooksOutBeDeactivated() {
        when(copies.findCopiesLentTo(MEMBER_ID)).thenReturn(List.of());

        guard.runGuarded(MEMBER_ID, () -> deactivated.set(true));

        assertThat(deactivated).isTrue();
    }

    @Test
    void refusesWhileTheMemberStillHasBooksOut() {
        when(copies.findCopiesLentTo(MEMBER_ID)).thenReturn(List.of(new BookCopyDetails(UUID.randomUUID(),
                UUID.randomUUID(), "LIB-000001", BookCopyStatus.LENT, Instant.now(), null)));

        assertThatThrownBy(() -> guard.runGuarded(MEMBER_ID, () -> deactivated.set(true)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Member " + MEMBER_ID + " still has 1 book out. They have to be returned before the "
                        + "member can be deactivated");
        assertThat(deactivated).isFalse();
    }
}
