package org.distributed.consensus.controller;

import org.distributed.consensus.model.BookingConfirmation;
import org.distributed.consensus.model.ConfirmBookingRequest;
import org.distributed.consensus.service.PessimisticBookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/pessimistic")
public class PessimisticBookingConfirmationController {

    @Autowired
    PessimisticBookingService bookingService;

    @PostMapping("/bookingconfirmation")
    public ResponseEntity<BookingConfirmation> createBooking(@Valid @RequestBody ConfirmBookingRequest request) {
        return new ResponseEntity<>(bookingService.confirm(request.bookingId()), HttpStatus.CREATED);
    }
}
