package com.assigment.paytm.ticketMaster.model;

import java.util.UUID;

public record ShowResponse(UUID id, String name, long price_paise, int per_user_limit, int total_seats) {
}
