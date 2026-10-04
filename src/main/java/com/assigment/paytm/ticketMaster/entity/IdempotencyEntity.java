package com.assigment.paytm.ticketMaster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyEntity {
    @EmbeddedId
    private IdempotencyId id;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "reservation_id")
    private UUID reservationId;

    protected IdempotencyEntity() {
    }

    public IdempotencyEntity(String userId, String key, String requestHash) {
        this.id = new IdempotencyId(userId, key);
        this.requestHash = requestHash;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getReservationId() {
        return reservationId;
    }
}
