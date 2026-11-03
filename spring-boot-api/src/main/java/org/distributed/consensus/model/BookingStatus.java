package org.distributed.consensus.model;

/**
 * Lifecycle of a booking.
 * <pre>
 *   RESERVED --confirm--> CONFIRMED
 *   RESERVED --delete---> CANCELLED
 *   RESERVED --5 min----> (expired, treated like CANCELLED)
 * </pre>
 * CONFIRMED and CANCELLED are final states.
 */
public enum BookingStatus {
    RESERVED,
    CONFIRMED,
    CANCELLED
}
