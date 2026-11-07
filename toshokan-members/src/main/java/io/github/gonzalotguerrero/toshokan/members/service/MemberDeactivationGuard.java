package io.github.gonzalotguerrero.toshokan.members.service;

import io.github.gonzalotguerrero.toshokan.members.dto.MemberLendingState;

/**
 * Lets another module decide whether a member can be deactivated. Members can't know about loans (circulation
 * depends on members, not the other way round), so circulation implements this: it refuses while the member still
 * has books out or a lend in progress.
 *
 * <p>The check is made against the member as read, and the deactivation is then written with
 * {@code IF version = ?} for that same read. A lend reserves its slot with the same kind of write, so if one
 * starts after the check, the deactivation fails instead of going through.
 */
public interface MemberDeactivationGuard {

    /**
     * Throws a {@link io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException} explaining why the
     * member can't be deactivated, or returns if they can.
     */
    void checkCanDeactivate(MemberLendingState member);
}
