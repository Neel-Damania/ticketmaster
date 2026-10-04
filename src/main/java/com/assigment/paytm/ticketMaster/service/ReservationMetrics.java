package com.assigment.paytm.ticketMaster.service;

import com.assigment.paytm.ticketMaster.entity.ShowEntity;
import com.assigment.paytm.ticketMaster.repository.SeatRepository;
import com.assigment.paytm.ticketMaster.repository.ShowRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ReservationMetrics {
    private final MeterRegistry meterRegistry;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final Counter confirmedReservations;

    public ReservationMetrics(
        MeterRegistry meterRegistry,
        SeatRepository seatRepository,
        ShowRepository showRepository
    ) {
        this.meterRegistry = meterRegistry;
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.confirmedReservations = Counter.builder("reservations.confirmed")
            .description("Reservations newly confirmed by this application instance")
            .register(meterRegistry);
        declineCounter("seat_taken");
        declineCounter("per_user_limit");
        declineCounter("idempotent_replay");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerExistingShows() {
        showRepository.findAll().stream()
            .map(ShowEntity::getId)
            .forEach(this::registerAvailableSeatsGauge);
    }

    public void registerShowGaugeAfterCommit(UUID showId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    registerAvailableSeatsGauge(showId);
                }
            });
            return;
        }
        registerAvailableSeatsGauge(showId);
    }

    public void recordDecline(String reason) {
        if ("seat_taken".equals(reason) || "per_user_limit".equals(reason) || "idempotent_replay".equals(reason)) {
            declineCounter(reason).increment();
        }
    }

    public void recordConfirmedAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    confirmedReservations.increment();
                }
            });
            return;
        }
        confirmedReservations.increment();
    }

    private void registerAvailableSeatsGauge(UUID showId) {
        Gauge.builder("seats_available", seatRepository, repository -> repository.countAvailable(showId))
            .description("Currently available seats, including holds whose expiry time has passed")
            .tag("show_id", showId.toString())
            .register(meterRegistry);
    }

    private Counter declineCounter(String reason) {
        return Counter.builder("reservations.declined")
            .description("Reservation declines and idempotent replays by reason")
            .tag("reason", reason)
            .register(meterRegistry);
    }
}
