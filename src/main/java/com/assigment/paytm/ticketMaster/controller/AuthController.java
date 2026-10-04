package com.assigment.paytm.ticketMaster.controller;

import com.assigment.paytm.ticketMaster.exception.DomainException;
import com.assigment.paytm.ticketMaster.model.AuthTokenRequest;
import com.assigment.paytm.ticketMaster.model.AuthTokenResponse;
import com.assigment.paytm.ticketMaster.service.AuthTokenService;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private static final Set<String> ROLES = Set.of("user", "admin");
    private final AuthTokenService authTokenService;

    public AuthController(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @PostMapping("/auth/token")
    public AuthTokenResponse createToken(@RequestBody AuthTokenRequest request) {
        if (request == null || request.user_id() == null || request.user_id().isBlank()) {
            throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "user_id is required");
        }
        String role = request.role() == null ? "user" : request.role();
        if (!ROLES.contains(role)) {
            throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "role must be either 'user' or 'admin'");
        }
        return new AuthTokenResponse(authTokenService.createToken(request.user_id(), role));
    }
}
