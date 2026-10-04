package com.assigment.paytm.ticketMaster.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_show_quota")
public class QuotaEntity {
    @EmbeddedId
    private QuotaId id;

    protected QuotaEntity() {
    }

    public QuotaEntity(QuotaId id) {
        this.id = id;
    }
}
