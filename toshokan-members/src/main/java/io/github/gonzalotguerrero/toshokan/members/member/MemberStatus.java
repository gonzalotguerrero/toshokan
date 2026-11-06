package io.github.gonzalotguerrero.toshokan.members.member;

/**
 * Whether someone can still borrow books. Members are never erased: deleting one makes them INACTIVE, so their
 * loan history keeps pointing at a real record, and they can be made ACTIVE again later.
 */
public enum MemberStatus {
    ACTIVE,
    INACTIVE
}
