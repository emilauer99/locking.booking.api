package org.distributed.consensus.service;

/**
 * Answered with 404 by the ApiExceptionHandler.
 */
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(long bookingId) {
        super("booking " + bookingId + " does not exist");
    }
}
