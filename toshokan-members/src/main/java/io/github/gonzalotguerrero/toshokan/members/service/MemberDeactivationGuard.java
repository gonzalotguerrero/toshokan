package io.github.gonzalotguerrero.toshokan.members.service;

import java.util.UUID;

/**
 * Lets another module decide whether a member can be deactivated right now. Members can't know about loans
 * (circulation depends on members, not the other way round), so circulation implements this: it refuses while
 * the member still has books out, and makes sure no new loan starts while the deactivation is being written.
 */
public interface MemberDeactivationGuard {

    /**
     * Runs {@code deactivation} if the member can be deactivated, or throws a
     * {@link io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException} explaining why not.
     */
    void runGuarded(UUID memberId, Runnable deactivation);
}
