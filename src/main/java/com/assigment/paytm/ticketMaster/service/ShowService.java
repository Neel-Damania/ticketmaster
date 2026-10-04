package com.assigment.paytm.ticketMaster.service;

import com.assigment.paytm.ticketMaster.exception.DomainException;
import com.assigment.paytm.ticketMaster.entity.SeatEntity;
import com.assigment.paytm.ticketMaster.entity.ShowEntity;
import com.assigment.paytm.ticketMaster.model.CreateShowRequest;
import com.assigment.paytm.ticketMaster.model.SeatResponse;
import com.assigment.paytm.ticketMaster.model.ShowResponse;
import com.assigment.paytm.ticketMaster.model.ShowStateResponse;
import com.assigment.paytm.ticketMaster.repository.SeatRepository;
import com.assigment.paytm.ticketMaster.repository.ShowRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {
    private static final Pattern SEAT_LABEL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,16}$");
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request, String actingUserRole) {
        if (actingUserRole == null || !"admin".equals(actingUserRole)) {
            throw new DomainException("forbidden", HttpStatus.FORBIDDEN.value(), "Only admins can create shows");
        }
        if (request == null) {
            throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Show payload is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Show name is required");
        }
        if (request.seats() == null || request.seats().isEmpty()) {
            throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "At least one seat is required");
        }
        List<String> labels = normalizeAndValidateSeats(request.seats());
        long pricePaise = Optional.ofNullable(request.price_paise()).orElse(0L);
        if (pricePaise < 0) {
            throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Price must be non-negative");
        }
        int perUserLimit = Optional.ofNullable(request.per_user_limit()).orElse(4);
        if (perUserLimit <= 0) {
            throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "per_user_limit must be greater than zero");
        }

        UUID showId = UUID.randomUUID();
        showRepository.save(new ShowEntity(showId, request.name(), pricePaise, perUserLimit, labels.size()));
        seatRepository.saveAll(labels.stream().map(label -> new SeatEntity(showId, label)).toList());
        return new ShowResponse(showId, request.name(), pricePaise, perUserLimit, labels.size());
    }

    @Transactional(readOnly = true)
    public ShowStateResponse getShow(UUID showId, boolean includeSeats) {
        ShowEntity show = showRepository.findById(showId)
            .orElseThrow(() -> new DomainException("not_found", HttpStatus.NOT_FOUND.value(), "Show not found"));

        SeatRepository.CountSummary counts = seatRepository.countShowState(showId);
        List<SeatResponse> seats = new ArrayList<>();
        if (includeSeats) {
            List<SeatRepository.SeatState> seatRows = seatRepository.findSeatStates(showId);
            for (SeatRepository.SeatState seatRow : seatRows) {
                seats.add(new SeatResponse(seatRow.getLabel(), seatRow.getStatus()));
            }
        }

        Map<String, Integer> countMap = Map.of(
            "available", counts.getAvailable(),
            "held", counts.getHeld(),
            "confirmed", counts.getConfirmed()
        );
        return new ShowStateResponse(show.getId(), show.getName(), show.getPricePaise(), show.getPerUserLimit(), show.getTotalSeats(), countMap, seats);
    }

    private List<String> normalizeAndValidateSeats(List<String> rawSeats) {
        LinkedHashSet<String> orderedSeats = new LinkedHashSet<>();
        for (String seat : rawSeats) {
            if (seat == null || seat.isBlank()) {
                throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Seat labels cannot be blank");
            }
            String normalized = seat.trim();
            if (!SEAT_LABEL_PATTERN.matcher(normalized).matches()) {
                throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Seat label is invalid: " + normalized);
            }
            if (!orderedSeats.add(normalized)) {
                throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Duplicate seat label: " + normalized);
            }
        }
        return new ArrayList<>(orderedSeats);
    }
}
