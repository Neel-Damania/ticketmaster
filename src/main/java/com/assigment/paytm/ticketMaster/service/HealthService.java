package com.assigment.paytm.ticketMaster.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HealthService {
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public HealthService(EntityManager entityManager, TransactionTemplate transactionTemplate) {
        this.entityManager = entityManager;
        this.transactionTemplate = transactionTemplate;
    }

    public boolean isDatabaseReady() {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                entityManager.createNativeQuery("SELECT 1").getSingleResult();
                return true;
            }));
        } catch (PersistenceException | DataAccessException | TransactionException ex) {
            return false;
        }
    }
}
