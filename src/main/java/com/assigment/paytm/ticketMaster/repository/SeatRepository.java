package com.assigment.paytm.ticketMaster.repository;

import com.assigment.paytm.ticketMaster.entity.SeatEntity;
import com.assigment.paytm.ticketMaster.entity.SeatId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<SeatEntity, SeatId> {
    @Query(value = """
        SELECT label, status, reservation_id AS "reservationId", user_id AS "userId",
               hold_expires_at AS "holdExpiresAt",
               (status = 'available' OR (status = 'held' AND hold_expires_at <= now())) AS free
        FROM seats
        WHERE show_id = :showId AND label IN (:labels)
        ORDER BY label
        FOR UPDATE
        """, nativeQuery = true)
    List<LockedSeat> lockSeatsForUpdate(@Param("showId") UUID showId, @Param("labels") List<String> labels);

    @Query(value = """
        SELECT label,
               CASE WHEN status = 'confirmed' THEN 'confirmed'
                    WHEN status = 'held' AND hold_expires_at > now() THEN 'held'
                    ELSE 'available' END AS status
        FROM seats
        WHERE show_id = :showId
        ORDER BY label
        """, nativeQuery = true)
    List<SeatState> findSeatStates(@Param("showId") UUID showId);

    @Query(value = """
        SELECT
            COUNT(*) FILTER (WHERE status = 'confirmed') AS confirmed,
            COUNT(*) FILTER (WHERE status = 'held' AND hold_expires_at > now()) AS held,
            COUNT(*) FILTER (WHERE status = 'available' OR (status = 'held' AND hold_expires_at <= now())) AS available
        FROM seats
        WHERE show_id = :showId
        """, nativeQuery = true)
    CountSummary countShowState(@Param("showId") UUID showId);

    @Query("""
        SELECT COUNT(s)
        FROM SeatEntity s
        WHERE s.id.showId = :showId AND s.userId = :userId
          AND (s.status = 'confirmed' OR (s.status = 'held' AND s.holdExpiresAt > CURRENT_TIMESTAMP))
        """)
    int countLiveSeats(@Param("showId") UUID showId, @Param("userId") String userId);

    @Query(value = """
        SELECT COUNT(*)
        FROM seats
        WHERE show_id = :showId
          AND (status = 'available' OR (status = 'held' AND hold_expires_at <= now()))
        """, nativeQuery = true)
    int countAvailable(@Param("showId") UUID showId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE seats
        SET status = 'held', reservation_id = :reservationId, user_id = :userId, hold_expires_at = :expiresAt
        WHERE show_id = :showId AND label IN (:labels)
          AND (status = 'available' OR (status = 'held' AND hold_expires_at <= now()))
        """, nativeQuery = true)
    int markSeatsHeld(
        @Param("showId") UUID showId,
        @Param("labels") List<String> labels,
        @Param("reservationId") UUID reservationId,
        @Param("userId") String userId,
        @Param("expiresAt") java.time.OffsetDateTime expiresAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE seats
        SET status = 'available', reservation_id = NULL, user_id = NULL, hold_expires_at = NULL
        WHERE reservation_id = :reservationId AND status IN ('held', 'confirmed')
        """, nativeQuery = true)
    int releaseReservationSeats(@Param("reservationId") UUID reservationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE seats SET status = 'confirmed', hold_expires_at = NULL
        WHERE reservation_id = :reservationId AND status = 'held' AND hold_expires_at > now()
        """, nativeQuery = true)
    int confirmReservationSeats(@Param("reservationId") UUID reservationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE seats
        SET status = 'available', reservation_id = NULL, user_id = NULL, hold_expires_at = NULL
        WHERE reservation_id = :reservationId AND status = 'held' AND hold_expires_at <= now()
        """, nativeQuery = true)
    int markExpiredHolds(@Param("reservationId") UUID reservationId);

    @Query(value = """
        SELECT EXISTS (
          SELECT 1 FROM seats
          WHERE show_id = :showId
            AND label IN (:labels)
            AND (status = 'confirmed' OR (status = 'held' AND hold_expires_at > now()))
            AND user_id IS DISTINCT FROM :userId)
        """, nativeQuery = true)
        boolean anyTakenByOthers(@Param("showId") UUID showId,
                         @Param("labels") List<String> labels,
                         @Param("userId") String userId);

    interface LockedSeat {
        String getLabel();
        String getStatus();
        UUID getReservationId();
        String getUserId();
        java.time.OffsetDateTime getHoldExpiresAt();
        boolean getFree();
    }

    interface SeatState {
        String getLabel();
        String getStatus();
    }

    interface CountSummary {
        int getConfirmed();
        int getHeld();
        int getAvailable();
    }
}
