package com.assigment.paytm.ticketMaster.service;

import com.assigment.paytm.ticketMaster.config.JwtProperties;
import com.assigment.paytm.ticketMaster.security.AuthenticatedUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {
    private final JwtProperties jwtProperties;

    public AuthTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public String createToken(String userId, String role) {
        SecretKey key = secretKey();
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId)
            .claim("role", role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(jwtProperties.getExpirationMs())))
            .signWith(key)
            .compact();
    }

    public AuthenticatedUser parseToken(String token) {
        SecretKey key = secretKey();
        Claims claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        return new AuthenticatedUser(claims.getSubject(), claims.get("role", String.class));
    }

    private SecretKey secretKey() {
        byte[] key = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            try {
                key = MessageDigest.getInstance("SHA-256").digest(key);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("JWT digest algorithm unavailable", e);
            }
        }
        return Keys.hmacShaKeyFor(key);
    }
}
