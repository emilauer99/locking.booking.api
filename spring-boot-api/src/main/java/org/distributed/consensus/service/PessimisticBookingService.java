package org.distributed.consensus.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.distributed.consensus.model.Booking;
import org.distributed.consensus.model.BookingConfirmation;
import org.distributed.consensus.model.BookingPolicies;
import org.distributed.consensus.model.CreateBookingRequest;
import org.distributed.consensus.repository.BookingConfirmationRepository;
import org.distributed.consensus.repository.BookingRepository;
import org.distributed.consensus.repository.RoomLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking process with pessimistic locking: every request first takes a lock in the
 * database and only then checks and changes the state, so parallel requests are
 * executed one after the other.
 * <ul>
 *     <li>new bookings: advisory lock on the room (see {@link RoomLockRepository}),
 *     then check for overlaps and insert the reservation</li>
 *     <li>confirm/delete: row lock on the booking (SELECT ... FOR UPDATE)</li>
 * </ul>
 * All locks are released at the end of the request's transaction. Between the requests
 * of the booking process the period is blocked by the reservation itself (status RESERVED
 * in the database, max. 5 minutes), not by an open transaction.
 */
@Service
public class PessimisticBookingService {

    private static final Logger log = LoggerFactory.getLogger(PessimisticBookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingConfirmationRepository confirmationRepository;
    private final RoomLockRepository roomLockRepository;
    private final Clock clock;
    private final Duration reservationTtl;

    public PessimisticBookingService(BookingRepository bookingRepository,
                                     BookingConfirmationRepository confirmationRepository,
                                     RoomLockRepository roomLockRepository,
                                     Clock clock,
                                     @Value("${booking.reservation-ttl:PT5M}") Duration reservationTtl) {
        this.bookingRepository = bookingRepository;
        this.confirmationRepository = confirmationRepository;
        this.roomLockRepository = roomLockRepository;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
    }

    @Transactional
    public Booking reserve(CreateBookingRequest request) {
        // it might happen for some locations that booking attempts to the same rooms are
        // made in parallel. Consequently it is better when we prevent this very early in the process:
        // lock the room before anything else, parallel requests for this room wait here
        roomLockRepository.lockRoom(request.roomId());

        Instant now = clock.instant();
        Booking booking = Booking.reserve(request.name(), request.roomId(), request.start(), request.finish(),
                now, reservationTtl);

        // unfortunately we cannot guarantee that all locations which might
        // evaluate to false here are not somehow offered to our customers
        // (e.g.: shared links in social medias, clients that are working
        // with old data ... )
        // Therefore this check is crucial
        if (!BookingPolicies.canBook(booking)) {
            throw new BookingConflictException("room " + booking.getRoomId() + " cannot be booked");
        }

        bookingRepository.expireOverlappingReservations(booking.getRoomId(), booking.getStart(), booking.getFinish(), now);

        // safe while holding the room lock: no other request can insert a booking for this room now
        if (bookingRepository.existsActiveOverlapping(booking.getRoomId(), booking.getStart(), booking.getFinish(), now)) {
            throw new BookingConflictException("room " + booking.getRoomId()
                    + " is already booked between " + booking.getStart() + " and " + booking.getFinish());
        }

        Booking saved = bookingRepository.saveAndFlush(booking);
        log.info("pessimistic: reserved booking {} for room {} ({} - {}) until {}",
                saved.getId(), saved.getRoomId(), saved.getStart(), saved.getFinish(), saved.getExpiresAt());
        return saved;
    }

    @Transactional
    public BookingConfirmation confirm(long bookingId) {
        // parallel confirm/delete requests for this booking wait here
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        BookingStates.ensureConfirmable(booking, clock.instant());
        booking.confirm();
        bookingRepository.save(booking);

        BookingConfirmation confirmation = confirmationRepository.saveAndFlush(new BookingConfirmation(bookingId));
        log.info("pessimistic: confirmed booking {}", bookingId);
        return confirmation;
    }

    @Transactional
    public void cancel(long bookingId) {
        // parallel confirm/delete requests for this booking wait here
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (BookingStates.cancel(booking)) {
            bookingRepository.save(booking);
            log.info("pessimistic: cancelled booking {}", bookingId);
        }
    }
}
