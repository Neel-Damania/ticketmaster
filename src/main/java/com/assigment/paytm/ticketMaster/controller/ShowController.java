package com.assigment.paytm.ticketMaster.controller;

import com.assigment.paytm.ticketMaster.model.CreateShowRequest;
import com.assigment.paytm.ticketMaster.model.ShowResponse;
import com.assigment.paytm.ticketMaster.model.ShowStateResponse;
import com.assigment.paytm.ticketMaster.security.AuthenticatedUser;
import com.assigment.paytm.ticketMaster.service.ShowService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {
    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> createShow(@RequestBody CreateShowRequest request,
                                                 @RequestAttribute(name = "user", required = false) AuthenticatedUser user) {
        String actingRole = user == null ? null : user.role();
        return ResponseEntity.status(HttpStatus.CREATED).body(showService.createShow(request, actingRole));
    }

    @GetMapping("/shows/{id}")
    public ShowStateResponse getShow(@PathVariable("id") UUID id,
                                    @RequestParam(name = "include_seats", defaultValue = "true") boolean includeSeats) {
        return showService.getShow(id, includeSeats);
    }
}
