package org.distributed.consensus.model;

/**
 * Lifecycle of a booking.
 * <pre>
 *   RESERVED --confirm--> CONFIRMED
 *   RESERVED --delete---> CANCELLED
 *   RESERVED --5 min----> EXPIRED
 * </pre>
 * CONFIRMED, CANCELLED and EXPIRED are final states.
 * A reservation is expired as soon as its expiresAt has passed, even if the status
 * still says RESERVED. The status is set to EXPIRED lazily, when a new booking needs
 * the period (otherwise the database constraint would still treat it as blocking).
 */
public enum BookingStatus {
    RESERVED,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
