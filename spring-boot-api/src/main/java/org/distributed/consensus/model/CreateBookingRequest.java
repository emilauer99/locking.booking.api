package org.distributed.consensus.model;

import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body of POST booking. Only contains the fields a client may choose,
 * status, expiry and version are always set by the api.
 */
public record CreateBookingRequest(
        @NotBlank String name,
        @NotNull @Positive Long roomId,
        @NotNull LocalDate start,
        @NotNull LocalDate finish) {

    @AssertTrue(message = "start must be before finish")
    public boolean isValidPeriod() {
        // null values are reported by @NotNull already
        return start == null || finish == null || start.isBefore(finish);
    }
}
