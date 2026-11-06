package io.github.gonzalotguerrero.toshokan.inventory.model;

/**
 * Where a copy is right now. New copies always start out available.
 *
 * <p>In the holdings, only {@link #AVAILABLE} copies count as on the shelf, and {@link #WITHDRAWN} ones don't
 * count at all. Withdrawing is how a copy gets deleted while we still keep its record. {@link #LENT} can't be
 * set or cleared by hand; only lending and returning will do that, so a copy is never lent without a loan.
 */
public enum BookCopyStatus {
    AVAILABLE,
    LENT,
    LOST,
    DAMAGED,
    IN_REPAIR,
    WITHDRAWN
}
