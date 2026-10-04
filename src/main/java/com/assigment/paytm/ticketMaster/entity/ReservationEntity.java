package com.assigment.paytm.ticketMaster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class ReservationEntity {
    @Id
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String seats;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected ReservationEntity() {
    }

    public ReservationEntity(UUID id, UUID showId, String userId, List<String> seats, long amountPaise, String status, OffsetDateTime expiresAt) {
        this.id = id;
        this.showId = showId;
        this.userId = userId;
        this.seats = String.join(",", seats);
        this.amountPaise = amountPaise;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getShowId() {
        return showId;
    }

    public String getUserId() {
        return userId;
    }

    public List<String> getSeats() {
        return Arrays.asList(seats.split(","));
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }
}
