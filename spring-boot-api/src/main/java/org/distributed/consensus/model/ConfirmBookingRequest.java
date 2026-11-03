package org.distributed.consensus.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body of POST bookingconfirmation.
 */
public record ConfirmBookingRequest(@NotNull @Positive Long bookingId) {
}
