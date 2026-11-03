package org.distributed.consensus.controller;

import org.distributed.consensus.model.BookingConfirmation;
import org.distributed.consensus.model.ConfirmBookingRequest;
import org.distributed.consensus.repository.BookingConfirmationRepository;
import org.distributed.consensus.service.OptimisticBookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/optimistic")
public class OptimisticBookingConfirmationController {

    @Autowired
    BookingConfirmationRepository confirmationRepository;

    @Autowired
    OptimisticBookingService bookingService;

    @GetMapping("/bookingconfirmation/{id}")
    public ResponseEntity<BookingConfirmation> getBookingConfirmations(@PathVariable long id) {
        Optional<BookingConfirmation> confirmation = this.confirmationRepository.findById(id);
        return new ResponseEntity<>(confirmation.orElse(null), confirmation.isEmpty() ? HttpStatus.NOT_FOUND : HttpStatus.OK);
    }

    @GetMapping("/bookingconfirmation")
    public ResponseEntity<List<BookingConfirmation>> getBookingConfirmations() {
        List<BookingConfirmation> allConfirmations = confirmationRepository.findAll();
        return new ResponseEntity<>(allConfirmations, allConfirmations.isEmpty() ? HttpStatus.NO_CONTENT : HttpStatus.OK);
    }

    @PostMapping("/bookingconfirmation")
    public ResponseEntity<BookingConfirmation> createBooking(@Valid @RequestBody ConfirmBookingRequest request) {
        return new ResponseEntity<>(bookingService.confirm(request.bookingId()), HttpStatus.CREATED);
    }
}
