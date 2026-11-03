package org.distributed.consensus.controller;

import org.distributed.consensus.model.Booking;
import org.distributed.consensus.model.CreateBookingRequest;
import org.distributed.consensus.service.PessimisticBookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/pessimistic")
public class PessimisticBookingController {

    @Autowired
    PessimisticBookingService bookingService;

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
