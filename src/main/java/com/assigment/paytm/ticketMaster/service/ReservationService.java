package com.assigment.paytm.ticketMaster.service;

import com.assigment.paytm.ticketMaster.config.HoldProperties;
import com.assigment.paytm.ticketMaster.entity.IdempotencyId;
import com.assigment.paytm.ticketMaster.entity.QuotaId;
import com.assigment.paytm.ticketMaster.entity.ReservationEntity;
import com.assigment.paytm.ticketMaster.exception.DomainException;
import com.assigment.paytm.ticketMaster.model.ReservationResponse;
import com.assigment.paytm.ticketMaster.repository.IdempotencyRepository;
import com.assigment.paytm.ticketMaster.repository.QuotaRepository;
import com.assigment.paytm.ticketMaster.repository.ReservationRepository;
import com.assigment.paytm.ticketMaster.repository.SeatRepository;
import com.assigment.paytm.ticketMaster.repository.ShowRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationService {
    private static final Pattern SEAT_LABEL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,16}$");
    private static final int GATE_COUNT = 1024;
    private static final int MAX_CACHED_SHOWS = 10_000;

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final QuotaRepository quotaRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TransactionTemplate transactionTemplate;
    private final HoldProperties holdProperties;
    private final ReservationMetrics reservationMetrics;

    // Optimisation only: losers of a seat race wait here (cheap, no DB connection held)
    // instead of queueing on a database row lock. The database still decides every success.
    private final ReentrantLock[] gates = IntStream.range(0, GATE_COUNT)
        .mapToObj(i -> new ReentrantLock())
        .toArray(ReentrantLock[]::new);

    // Caps how many reserve requests touch the database at once, so the Hikari pool is never
    // oversubscribed (no pool-timeout 500s) and other endpoints still get connections.
    private final Semaphore dbWork;

    // Shows are never edited after creation, so the two fields reserve needs are safe to cache.
    private final ConcurrentHashMap<UUID, ShowInfo> showCache = new ConcurrentHashMap<>();

    public ReservationService(
        ShowRepository showRepository,
        SeatRepository seatRepository,
        ReservationRepository reservationRepository,
        QuotaRepository quotaRepository,
        IdempotencyRepository idempotencyRepository,
        TransactionTemplate transactionTemplate,
        HoldProperties holdProperties,
        ReservationMetrics reservationMetrics,
        @Value("${reservation.db-concurrency:16}") int dbConcurrency
    ) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.quotaRepository = quotaRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.transactionTemplate = transactionTemplate;
        this.holdProperties = holdProperties;
        this.reservationMetrics = reservationMetrics;
        this.dbWork = new Semaphore(dbConcurrency);
    }

    public ReservationResult reserveWithStatus(UUID showId, String userId, List<String> requestedSeats, String idempotencyHeader, String idempotencyBody) {
        List<String> seats = normalizeAndValidateSeats(requestedSeats);
        String idempotencyKey = firstNonBlank(idempotencyHeader, idempotencyBody);
        String requestHash = sha256(showId + ":" + String.join(",", seats));

        ShowInfo show = loadShow(showId);
        if (seats.size() > show.perUserLimit()) {
            reservationMetrics.recordDecline("per_user_limit");
            throw new DomainException("per_user_limit", HttpStatus.CONFLICT.value(), "Requested more seats than the per-user limit allows");
        }

        // Gates are taken in sorted order, so two multi-seat requests cannot wait on each other in a cycle.
        List<ReentrantLock> held = gatesFor(showId, seats);
        held.forEach(ReentrantLock::lock);
        try {
            dbWork.acquireUninterruptibly();
            try {
                rejectIfTaken(showId, userId, seats, idempotencyKey);
                return runReservation(showId, show, userId, seats, idempotencyKey, requestHash);
            } finally {
                dbWork.release();
            }
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        }
    }

    // Cheap pre-check outside any transaction. It can only reject: a "taken" answer was true at the
    // moment of the read, and a "free" answer just falls through to the transaction, which decides.
    // Requests whose idempotency key already exists skip it so replays still return the original reservation.
    private void rejectIfTaken(UUID showId, String userId, List<String> seats, String idempotencyKey) {
        boolean knownKey = idempotencyKey != null && !idempotencyKey.isBlank()
            && idempotencyRepository.existsById(new IdempotencyId(userId, idempotencyKey));
        if (!knownKey && seatRepository.anyTakenByOthers(showId, seats, userId)) {
            reservationMetrics.recordDecline("seat_taken");
            throw new DomainException("seat_taken", HttpStatus.CONFLICT.value(), "One or more seats are already taken");
        }
    }

    private ReservationResult runReservation(UUID showId, ShowInfo show, String userId, List<String> seats, String idempotencyKey, String requestHash) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ReservationResult result = transactionTemplate.execute(status -> {
                    // Handle idempotency key if provided for Reservation
                    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        int inserted = idempotencyRepository.insertIfAbsent(userId, idempotencyKey, requestHash);
                        if (inserted == 0) {
                            var idempotencyRow = idempotencyRepository.findById(new IdempotencyId(userId, idempotencyKey))
                                .orElseThrow(() -> new DomainException("idempotency_conflict", HttpStatus.CONFLICT.value(), "Idempotency key conflict"));
                            if (!requestHash.equals(idempotencyRow.getRequestHash())) {
                                throw new DomainException("idempotency_conflict", HttpStatus.CONFLICT.value(), "The idempotency key was used with different seats");
                            }
                            ReservationEntity reservation = reservationRepository.findById(idempotencyRow.getReservationId())
                                .orElseThrow(() -> new DomainException("not_found", HttpStatus.NOT_FOUND.value(), "Reservation not found"));
                            return new ReservationResult(toResponse(reservation), true);
                        }
                    }

                    // Lock order: idempotency row -> quota row -> seat rows (sorted by label)
                    quotaRepository.insertQuotaIfAbsent(showId, userId);
                    quotaRepository.lockQuota(new QuotaId(showId, userId))
                        .orElseThrow(() -> new IllegalStateException("Quota row was not created"));
                    int liveCount = seatRepository.countLiveSeats(showId, userId);
                    if (liveCount + seats.size() > show.perUserLimit()) {
                        throw new DomainException("per_user_limit", HttpStatus.CONFLICT.value(), "Per-user limit reached");
                    }

                    List<SeatRepository.LockedSeat> lockedSeats = seatRepository.lockSeatsForUpdate(showId, seats);
                    if (lockedSeats.size() < seats.size()) {
                        throw new DomainException("not_found", HttpStatus.NOT_FOUND.value(), "Unknown seat requested");
                    }
                    for (SeatRepository.LockedSeat seat : lockedSeats) {
                        if (!isSeatFree(seat)) {
                            throw new DomainException("seat_taken", HttpStatus.CONFLICT.value(), "Seat already taken: " + seat.getLabel());
                        }
                    }

                    long databaseNowMicros = showRepository.databaseNowEpochMicros();
                    Instant databaseNow = Instant.ofEpochSecond(
                        databaseNowMicros / 1_000_000,
                        databaseNowMicros % 1_000_000 * 1_000
                    );
                    OffsetDateTime expiresAt = databaseNow.atOffset(ZoneOffset.UTC).plusSeconds(holdProperties.getTtlSeconds());
                    UUID reservationId = UUID.randomUUID();
                    int updated = seatRepository.markSeatsHeld(showId, seats, reservationId, userId, expiresAt);
                    if (updated != seats.size()) {
                        throw new DomainException("seat_taken", HttpStatus.CONFLICT.value(), "Seat was no longer available");
                    }

                    long amountPaise;
                    try {
                        amountPaise = Math.multiplyExact(show.pricePaise(), seats.size());
                    } catch (ArithmeticException ex) {
                        throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Reservation amount is too large");
                    }
                    reservationRepository.save(new ReservationEntity(
                        reservationId,
                        showId,
                        userId,
                        seats,
                        amountPaise,
                        "held",
                        expiresAt
                    ));

                    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        idempotencyRepository.updateReservationId(userId, idempotencyKey, reservationId);
                    }

                    return new ReservationResult(new ReservationResponse(
                        reservationId,
                        showId,
                        userId,
                        seats,
                        amountPaise,
                        "held",
                        expiresAt
                    ), false);
                });
                if (result.replay()) {
                    reservationMetrics.recordDecline("idempotent_replay");
                }
                return result;
            } catch (PessimisticLockingFailureException ex) {
                if (attempt == 2) {
                    throw ex;
                }
                backoff(attempt);
            } catch (DomainException ex) {
                reservationMetrics.recordDecline(ex.getCode());
                throw ex;
            }
        }
        throw new IllegalStateException("Reservation transaction failed after retries");
    }

    public ReservationResponse reserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyHeader, String idempotencyBody) {
        return reserveWithStatus(showId, userId, requestedSeats, idempotencyHeader, idempotencyBody).response();
    }

    public ReservationResponse confirm(UUID reservationId, String actingUserId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    ReservationEntity reservation = reservationRepository.findByIdForUpdate(reservationId)
                        .orElseThrow(() -> new DomainException("not_found", HttpStatus.NOT_FOUND.value(), "Reservation not found"));
                    if (!reservation.getUserId().equals(actingUserId)) {
                        throw new DomainException("forbidden", HttpStatus.FORBIDDEN.value(), "User does not own this reservation");
                    }
                    if ("confirmed".equals(reservation.getStatus())) {
                        return toResponse(reservation);
                    }
                    if ("cancelled".equals(reservation.getStatus())) {
                        throw new DomainException("reservation_cancelled", HttpStatus.CONFLICT.value(), "Reservation is already cancelled");
                    }
                    if ("expired".equals(reservation.getStatus())
                        || ("held".equals(reservation.getStatus()) && !reservationRepository.isLiveHold(reservationId))) {
                        throw new DomainException("hold_expired", HttpStatus.CONFLICT.value(), "Hold is expired");
                    }

                    int updated = seatRepository.confirmReservationSeats(reservationId);
                    if (updated == 0) {
                        throw new DomainException("hold_expired", HttpStatus.CONFLICT.value(), "Hold is expired");
                    }
                    reservationRepository.updateStatus(reservationId, "confirmed");
                    reservationMetrics.recordConfirmedAfterCommit();
                    return reservationRepository.findById(reservationId)
                        .map(this::toResponse)
                        .orElseThrow(() -> new DomainException("not_found", HttpStatus.NOT_FOUND.value(), "Reservation not found"));
                });
            } catch (PessimisticLockingFailureException ex) {
                if (attempt == 2) {
                    throw ex;
                }
                backoff(attempt);
            }
        }
        throw new IllegalStateException("Confirm transaction failed after retries");
    }

    public ReservationResponse cancel(UUID reservationId, String actingUserId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    ReservationEntity reservation = reservationRepository.findByIdForUpdate(reservationId)
                        .orElseThrow(() -> new DomainException("not_found", HttpStatus.NOT_FOUND.value(), "Reservation not found"));
                    if (!reservation.getUserId().equals(actingUserId)) {
                        throw new DomainException("forbidden", HttpStatus.FORBIDDEN.value(), "User does not own this reservation");
                    }
                    if ("cancelled".equals(reservation.getStatus()) || "expired".equals(reservation.getStatus())) {
                        return toResponse(reservation);
                    }

                    seatRepository.releaseReservationSeats(reservationId);
                    reservationRepository.updateStatus(reservationId, "cancelled");
                    return reservationRepository.findById(reservationId)
                        .map(this::toResponse)
                        .orElseThrow(() -> new DomainException("not_found", HttpStatus.NOT_FOUND.value(), "Reservation not found"));
                });
            } catch (PessimisticLockingFailureException ex) {
                if (attempt == 2) {
                    throw ex;
                }
                backoff(attempt);
            }
        }
        throw new IllegalStateException("Cancel transaction failed after retries");
    }

    private ShowInfo loadShow(UUID showId) {
        ShowInfo cached = showCache.get(showId);
        if (cached != null) {
            return cached;
        }

        // A new show can receive a burst before it is cached. Let one caller read it from Postgres.
        synchronized (showCache) {
            cached = showCache.get(showId);
            if (cached != null) {
                return cached;
            }

            var show = showRepository.findById(showId).orElseThrow(() -> {
                reservationMetrics.recordDecline("not_found");
                return new DomainException("not_found", HttpStatus.NOT_FOUND.value(), "Show not found");
            });
            ShowInfo info = new ShowInfo(show.getPerUserLimit(), show.getPricePaise());
            if (showCache.size() >= MAX_CACHED_SHOWS) {
                showCache.clear();
            }
            showCache.put(showId, info);
            return info;
        }
    }

    private List<ReentrantLock> gatesFor(UUID showId, List<String> seats) {
        return seats.stream()
            .map(seat -> Math.floorMod(Objects.hash(showId, seat), GATE_COUNT))
            .distinct()
            .sorted()
            .map(index -> gates[index])
            .toList();
    }

    // Short randomised pause so two transactions that just deadlocked do not collide again immediately.
    private void backoff(int attempt) {
        try {
            Thread.sleep(5L * (attempt + 1) + ThreadLocalRandom.current().nextInt(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isSeatFree(SeatRepository.LockedSeat seatRow) {
        return seatRow.getFree();
    }

    private List<String> normalizeAndValidateSeats(List<String> requestedSeats) {
        if (requestedSeats == null || requestedSeats.isEmpty()) {
            throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "At least one seat is required");
        }
        List<String> normalized = new ArrayList<>();
        for (String rawSeat : requestedSeats) {
            if (rawSeat == null || rawSeat.isBlank()) {
                throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Seat labels cannot be blank");
            }
            String candidate = rawSeat.trim();
            if (!SEAT_LABEL_PATTERN.matcher(candidate).matches()) {
                throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Seat label is invalid: " + candidate);
            }
            if (normalized.contains(candidate)) {
                throw new DomainException("bad_request", HttpStatus.BAD_REQUEST.value(), "Duplicate seat label: " + candidate);
            }
            normalized.add(candidate);
        }
        normalized.sort(String::compareTo);
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record ReservationResult(ReservationResponse response, boolean replay) {
    }

    private record ShowInfo(int perUserLimit, long pricePaise) {
    }

    private ReservationResponse toResponse(ReservationEntity reservation) {
        return new ReservationResponse(
            reservation.getId(),
            reservation.getShowId(),
            reservation.getUserId(),
            reservation.getSeats(),
            reservation.getAmountPaise(),
            reservation.getStatus(),
            reservation.getExpiresAt()
        );
    }
}
