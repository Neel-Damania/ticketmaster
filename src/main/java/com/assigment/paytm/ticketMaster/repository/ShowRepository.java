package com.assigment.paytm.ticketMaster.repository;

import com.assigment.paytm.ticketMaster.entity.ShowEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
    @Query(value = "SELECT (extract(epoch FROM now()) * 1000000)::bigint", nativeQuery = true)
    long databaseNowEpochMicros();
}
