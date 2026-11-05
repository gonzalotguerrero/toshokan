package io.github.gonzalotguerrero.toshokan.inventory.bookcopy;

/**
 * Where a copy is right now. The holdings only count {@link #AVAILABLE} copies as on the shelf. New copies
 * always start out available; the other statuses come into play with lending and copy management.
 */
public enum BookCopyStatus {
    AVAILABLE,
    LENT,
    LOST,
    DAMAGED,
    IN_REPAIR,
    WITHDRAWN
}
