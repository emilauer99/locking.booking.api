package org.distributed.consensus.service;

import java.time.Instant;

import org.distributed.consensus.model.Booking;

/**
 * State transitions of the booking process, shared by the optimistic and the pessimistic variant.
 * The variants only differ in how they make sure that the state they check is still the
 * current one when they write.
 */
final class BookingStates {

    private BookingStates() {
    }

    /**
     * Only a reservation that is neither cancelled nor expired can be confirmed.
     */
    static void ensureConfirmable(Booking booking, Instant now) {
        long id = booking.getId();
        switch (booking.getStatus()) {
            case CONFIRMED -> throw new BookingConflictException("booking " + id + " is already confirmed");
            case CANCELLED -> throw new BookingConflictException("booking " + id + " was cancelled");
            case EXPIRED -> throw new BookingConflictException("reservation " + id + " has expired");
            case RESERVED -> {
                if (booking.isExpired(now)) {
                    throw new BookingConflictException("reservation " + id + " has expired");
                }
            }
        }
    }

    /**
     * Cancels a reservation.
     *
     * @return true if the booking was changed, false if there was nothing to do
     * (already cancelled or expired - deleting is idempotent)
     */
    static boolean cancel(Booking booking) {
        return switch (booking.getStatus()) {
            case CONFIRMED -> throw new BookingConflictException("booking " + booking.getId() + " is already confirmed");
            case CANCELLED, EXPIRED -> false;
            case RESERVED -> {
                booking.cancel();
                yield true;
            }
        };
    }
}
