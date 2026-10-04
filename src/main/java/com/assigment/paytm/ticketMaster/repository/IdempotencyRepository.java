package com.assigment.paytm.ticketMaster.repository;

import com.assigment.paytm.ticketMaster.entity.IdempotencyEntity;
import com.assigment.paytm.ticketMaster.entity.IdempotencyId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyEntity, IdempotencyId> {
    @Modifying
    @Query(value = """
        INSERT INTO idempotency_keys(user_id, idem_key, request_hash, reservation_id)
        VALUES (:userId, :key, :requestHash, NULL)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("userId") String userId,
        @Param("key") String key,
        @Param("requestHash") String requestHash
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE idempotency_keys SET reservation_id = :reservationId
        WHERE user_id = :userId AND idem_key = :key
        """, nativeQuery = true)
    int updateReservationId(
        @Param("userId") String userId,
        @Param("key") String key,
        @Param("reservationId") UUID reservationId
    );
}
