package org.distributed.consensus.controller;

import org.distributed.consensus.model.Booking;
import org.distributed.consensus.model.BookingPolicies;
import org.distributed.consensus.model.BookingStatus;
import org.distributed.consensus.model.CreateBookingRequest;
import org.distributed.consensus.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/optimistic")
public class OptimisticBookingController {

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    Clock clock;

    @Value("${booking.reservation-ttl:PT5M}")
    Duration reservationTtl;

    @GetMapping("/booking/{id}")
    public ResponseEntity<Booking> getBooking(@PathVariable long id) {
        Optional<Booking> booking = this.bookingRepository.findById(id);
        return new ResponseEntity<>(booking.orElse(null), booking.isEmpty() ? HttpStatus.NOT_FOUND : HttpStatus.OK);
    }

    @GetMapping("/booking")
    public ResponseEntity<List<Booking>> getBooking() {
        List<Booking> allBookings = this.bookingRepository.findAll();
        return new ResponseEntity<>(allBookings, HttpStatus.OK);
    }

    @PostMapping("/booking")
    public ResponseEntity<Booking> createBooking(@Valid @RequestBody CreateBookingRequest request) {
        Booking booking = Booking.reserve(request.name(), request.roomId(), request.start(), request.finish(),
                clock.instant(), reservationTtl);

        // unfortunately we cannot guarantee that all locations which might
        // evaluate to false here are not somehow offered to our customers
        // (e.g.: shared links in social medias, clients that are working
        // with old data ... )
        // Therefore this check is crucial
        if (!BookingPolicies.canBook(booking)) {
            return new ResponseEntity<>(null, HttpStatus.CONFLICT);
        }

        // TODO: this check alone is not safe against parallel requests (check-then-act),
        // the database has to reject overlapping bookings (optimistic locking)
        if (bookingRepository.existsActiveOverlapping(booking.getRoomId(), booking.getStart(), booking.getFinish(), clock.instant())) {
            return new ResponseEntity<>(null, HttpStatus.CONFLICT);
        }

        Booking saved = bookingRepository.save(booking);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @DeleteMapping("/booking/{id}")
    public ResponseEntity<HttpStatus> deleteBooking(@PathVariable("id") long id) {
        Optional<Booking> found = bookingRepository.findById(id);
        if (found.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        // bookings are not deleted physically but cancelled, so a later
        // confirmation can still see that the booking was cancelled
        Booking booking = found.get();
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            // a confirmed booking cannot be cancelled anymore
            return new ResponseEntity<>(HttpStatus.CONFLICT);
        }
        if (booking.getStatus() == BookingStatus.RESERVED) {
            booking.cancel();
            bookingRepository.save(booking);
        }
        // deleting an already cancelled booking is idempotent
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
