package org.distributed.consensus.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.distributed.consensus.model.Booking;
import org.distributed.consensus.model.BookingConfirmation;
import org.distributed.consensus.model.BookingPolicies;
import org.distributed.consensus.model.CreateBookingRequest;
import org.distributed.consensus.repository.BookingConfirmationRepository;
import org.distributed.consensus.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking process with optimistic locking: nothing is locked up front, every request
 * assumes that there is no conflict. Conflicts are detected by the database when the
 * change is written:
 * <ul>
 *     <li>new bookings: exclusion constraint bookings_no_overlap (no overlapping
 *     RESERVED/CONFIRMED booking for the same room)</li>
 *     <li>confirm/delete: version column of the booking (UPDATE ... WHERE version = ?)
 *     and the unique constraint on bookingsconfirmation.booking_id</li>
 * </ul>
 * As the state lives in the database, this works across all api instances.
 */
@Service
public class OptimisticBookingService {

    // PostgreSQL error codes, see https://www.postgresql.org/docs/current/errcodes-appendix.html
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String UNIQUE_VIOLATION = "23505";

    private static final Logger log = LoggerFactory.getLogger(OptimisticBookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingConfirmationRepository confirmationRepository;
    private final Clock clock;
    private final Duration reservationTtl;

    public OptimisticBookingService(BookingRepository bookingRepository,
                                    BookingConfirmationRepository confirmationRepository,
                                    Clock clock,
                                    @Value("${booking.reservation-ttl:PT5M}") Duration reservationTtl) {
        this.bookingRepository = bookingRepository;
        this.confirmationRepository = confirmationRepository;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
    }

    @Transactional
    public Booking reserve(CreateBookingRequest request) {
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

        // expired reservations must not block the period in the exclusion constraint
        bookingRepository.expireOverlappingReservations(booking.getRoomId(), booking.getStart(), booking.getFinish(), now);

        // as it is expected that a collisions of bookings are rare
        // we believe that we will not run into this error here very often:
        // no check up front, the constraint decides when the insert is executed
        try {
            Booking saved = bookingRepository.saveAndFlush(booking);
            log.info("optimistic: reserved booking {} for room {} ({} - {}) until {}",
                    saved.getId(), saved.getRoomId(), saved.getStart(), saved.getFinish(), saved.getExpiresAt());
            return saved;
        } catch (DataIntegrityViolationException e) {
            if (EXCLUSION_VIOLATION.equals(sqlState(e))) {
                throw new BookingConflictException("room " + booking.getRoomId()
                        + " is already booked between " + booking.getStart() + " and " + booking.getFinish(), e);
            }
            throw e;
        }
    }

    @Transactional
    public BookingConfirmation confirm(long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        BookingStates.ensureConfirmable(booking, clock.instant());
        booking.confirm();

        try {
            // UPDATE bookings ... WHERE id = ? AND version = ?
            // fails if the booking was confirmed, cancelled or expired in the meantime
            bookingRepository.saveAndFlush(booking);
            BookingConfirmation confirmation = confirmationRepository.saveAndFlush(new BookingConfirmation(bookingId));
            log.info("optimistic: confirmed booking {}", bookingId);
            return confirmation;
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BookingConflictException("booking " + bookingId + " was modified concurrently", e);
        } catch (DataIntegrityViolationException e) {
            if (UNIQUE_VIOLATION.equals(sqlState(e))) {
                throw new BookingConflictException("booking " + bookingId + " is already confirmed", e);
            }
            throw e;
        }
    }

    @Transactional
    public void cancel(long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (!BookingStates.cancel(booking)) {
            return;
        }

        try {
            // fails if the booking was confirmed in the meantime
            bookingRepository.saveAndFlush(booking);
            log.info("optimistic: cancelled booking {}", bookingId);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BookingConflictException("booking " + bookingId + " was modified concurrently", e);
        }
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
