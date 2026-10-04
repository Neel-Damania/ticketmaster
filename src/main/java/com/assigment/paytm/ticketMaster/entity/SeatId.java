package com.assigment.paytm.ticketMaster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SeatId implements Serializable {
    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(nullable = false)
    private String label;

    protected SeatId() {
    }

    public SeatId(UUID showId, String label) {
        this.showId = showId;
        this.label = label;
    }

    public UUID getShowId() {
        return showId;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SeatId seatId)) {
            return false;
        }
        return Objects.equals(showId, seatId.showId) && Objects.equals(label, seatId.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showId, label);
    }
}
