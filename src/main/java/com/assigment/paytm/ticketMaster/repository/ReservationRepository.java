package com.assigment.paytm.ticketMaster.repository;

import com.assigment.paytm.ticketMaster.entity.ReservationEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReservationEntity r WHERE r.id = :id")
    Optional<ReservationEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
        SELECT id, show_id, user_id, seats, amount_paise, status, expires_at
        FROM reservations
        WHERE status = 'held' AND expires_at <= now()
        ORDER BY expires_at
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<ReservationEntity> findExpiredHeldReservations(@Param("limit") int limit);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM reservations
            WHERE id = :id AND status = 'held' AND expires_at > now()
        )
        """, nativeQuery = true)
    boolean isLiveHold(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE reservations SET status = :status WHERE id = :id", nativeQuery = true)
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE reservations SET status = 'expired'
        WHERE id = :id AND status = 'held' AND expires_at <= now()
        """, nativeQuery = true)
    int markExpired(@Param("id") UUID id);
}
