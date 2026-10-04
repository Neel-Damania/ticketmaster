package com.assigment.paytm.ticketMaster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class QuotaId implements Serializable {
    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    protected QuotaId() {
    }

    public QuotaId(UUID showId, String userId) {
        this.showId = showId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QuotaId quotaId)) {
            return false;
        }
        return Objects.equals(showId, quotaId.showId) && Objects.equals(userId, quotaId.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showId, userId);
    }
}
