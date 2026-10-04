package com.assigment.paytm.ticketMaster.service;

import com.assigment.paytm.ticketMaster.config.HoldProperties;
import com.assigment.paytm.ticketMaster.entity.ReservationEntity;
import com.assigment.paytm.ticketMaster.repository.ReservationRepository;
import com.assigment.paytm.ticketMaster.repository.SeatRepository;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HoldExpiryService {
    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final TransactionTemplate transactionTemplate;
    private final HoldProperties holdProperties;

    public HoldExpiryService(
        ReservationRepository reservationRepository,
        SeatRepository seatRepository,
        TransactionTemplate transactionTemplate,
        HoldProperties holdProperties
    ) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.transactionTemplate = transactionTemplate;
        this.holdProperties = holdProperties;
    }

    @Scheduled(fixedDelayString = "${hold.sweep-interval-ms:5000}")
    public void sweepExpiredHolds() {
        transactionTemplate.executeWithoutResult(status -> {
            List<ReservationEntity> expiredReservations = reservationRepository.findExpiredHeldReservations(holdProperties.getSweepBatch());
            for (ReservationEntity reservation : expiredReservations) {
                reservationRepository.markExpired(reservation.getId());
                seatRepository.markExpiredHolds(reservation.getId());
            }
        });
    }
}
