package com.assigment.paytm.ticketMaster.model;

import java.util.List;

public record ReserveRequest(List<String> seats, String idempotency_key) {
}
