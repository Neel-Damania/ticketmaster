package com.assigment.paytm.ticketMaster.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ShowStateResponse(
    UUID id,
    String name,
    long price_paise,
    int per_user_limit,
    int total_seats,
    Map<String, Integer> counts,
    List<SeatResponse> seats
) {
}
