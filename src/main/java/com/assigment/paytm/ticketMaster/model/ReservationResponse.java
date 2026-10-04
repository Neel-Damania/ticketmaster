package com.assigment.paytm.ticketMaster.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
    UUID reservation_id,
    UUID show_id,
    String user_id,
    List<String> seats,
    long amount_paise,
    String status,
    OffsetDateTime expires_at
) {
}
