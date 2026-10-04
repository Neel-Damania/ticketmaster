package com.assigment.paytm.ticketMaster.controller;

import com.assigment.paytm.ticketMaster.model.ReservationResponse;
import com.assigment.paytm.ticketMaster.model.ReserveRequest;
import com.assigment.paytm.ticketMaster.security.AuthenticatedUser;
import com.assigment.paytm.ticketMaster.service.ReservationService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable("id") UUID showId,
                                                     @RequestBody ReserveRequest request,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
                                                     @RequestAttribute(name = "user", required = false) AuthenticatedUser user) {
        String actingUserId = user == null ? null : user.userId();
        ReservationService.ReservationResult result = reservationService.reserveWithStatus(showId, actingUserId, request == null ? null : request.seats(), idempotencyHeader, request == null ? null : request.idempotency_key());
        if (result.replay()) {
            return ResponseEntity.ok(result.response());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
    }

    @PostMapping("/reservations/{id}/confirm")
    public ReservationResponse confirm(@PathVariable("id") UUID reservationId,
                                      @RequestAttribute(name = "user", required = false) AuthenticatedUser user) {
        return reservationService.confirm(reservationId, user == null ? null : user.userId());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(@PathVariable("id") UUID reservationId,
                                    @RequestAttribute(name = "user", required = false) AuthenticatedUser user) {
        return reservationService.cancel(reservationId, user == null ? null : user.userId());
    }
}
