package com.assigment.paytm.ticketMaster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class IdempotencyId implements Serializable {
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "idem_key", nullable = false)
    private String key;

    protected IdempotencyId() {
    }

    public IdempotencyId(String userId, String key) {
        this.userId = userId;
        this.key = key;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdempotencyId idempotencyId)) {
            return false;
        }
        return Objects.equals(userId, idempotencyId.userId) && Objects.equals(key, idempotencyId.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, key);
    }
}
