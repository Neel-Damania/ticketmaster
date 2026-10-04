package com.assigment.paytm.ticketMaster.repository;

import com.assigment.paytm.ticketMaster.entity.QuotaEntity;
import com.assigment.paytm.ticketMaster.entity.QuotaId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotaRepository extends JpaRepository<QuotaEntity, QuotaId> {
    @Modifying
    @Query(value = """
        INSERT INTO user_show_quota(show_id, user_id) VALUES (:showId, :userId)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertQuotaIfAbsent(@Param("showId") java.util.UUID showId, @Param("userId") String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM QuotaEntity q WHERE q.id = :id")
    Optional<QuotaEntity> lockQuota(@Param("id") QuotaId id);
}
