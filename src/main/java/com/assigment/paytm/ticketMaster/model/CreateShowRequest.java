package com.assigment.paytm.ticketMaster.model;

import java.util.List;

public record CreateShowRequest(String name, List<String> seats, Long price_paise, Integer per_user_limit) {
}
