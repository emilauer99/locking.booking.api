package org.distributed.consensus.controller;

import org.distributed.consensus.model.Booking;
import org.distributed.consensus.model.CreateBookingRequest;
import org.distributed.consensus.repository.BookingRepository;
import org.distributed.consensus.service.BookingNotFoundException;
import org.distributed.consensus.service.OptimisticBookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/optimistic")
public class OptimisticBookingController {

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    OptimisticBookingService bookingService;

    @GetMapping("/booking/{id}")
    public ResponseEntity<Booking> getBooking(@PathVariable long id) {
        Booking booking = this.bookingRepository.findById(id).orElseThrow(() -> new BookingNotFoundException(id));
        return new ResponseEntity<>(booking, HttpStatus.OK);
    }

    @GetMapping("/booking")
    public ResponseEntity<List<Booking>> getBooking() {
        List<Booking> allBookings = this.bookingRepository.findAll();
        return new ResponseEntity<>(allBookings, HttpStatus.OK);
    }

    @PostMapping("/booking")
    public ResponseEntity<Booking> createBooking(@Valid @RequestBody CreateBookingRequest request) {
        return new ResponseEntity<>(bookingService.reserve(request), HttpStatus.CREATED);
    }

    @DeleteMapping("/booking/{id}")
    public ResponseEntity<HttpStatus> deleteBooking(@PathVariable("id") long id) {
        bookingService.cancel(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
