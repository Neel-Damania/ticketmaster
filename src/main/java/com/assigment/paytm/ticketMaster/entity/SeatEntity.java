package com.assigment.paytm.ticketMaster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "seats")
public class SeatEntity {
    @EmbeddedId
    private SeatId id;

    @Column(nullable = false)
    private String status;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "hold_expires_at")
    private OffsetDateTime holdExpiresAt;

    protected SeatEntity() {
    }

    public SeatEntity(UUID showId, String label) {
        this.id = new SeatId(showId, label);
        this.status = "available";
    }

    public SeatId getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getUserId() {
        return userId;
    }

    public OffsetDateTime getHoldExpiresAt() {
        return holdExpiresAt;
    }
}
