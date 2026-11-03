package org.distributed.consensus.service;

/**
 * The request is valid, but cannot be executed in the current state of the booking
 * (overlapping booking, already confirmed/cancelled/expired, concurrent modification).
 * Answered with 409 by the ApiExceptionHandler.
 */
public class BookingConflictException extends RuntimeException {

    public BookingConflictException(String message) {
        super(message);
    }

    public BookingConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
