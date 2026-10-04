# Seat reservation service

Take-home for Paytm Money (backend). It's a Spring Boot service that sells numbered seats for a show: reserve places a time-boxed hold, confirm makes it a booking, and holds that aren't confirmed in time go back to available on their own. Right now the goal is a working set of APIs that I can test in Postman. Packaging, deployment and observability come later, step by step.

Read docs/SPEC.md, docs/DESIGN.md and docs/TASKS.md before touching anything.

## Stack

Java 21, Spring Boot, Maven, Postgres as the only datastore. Spring Data JPA/Hibernate repositories + TransactionTemplate (inside services); native PostgreSQL queries are used only for the conditional updates, database-clock checks, and row-locking behavior required by the reservation rules. The schema is src/main/resources/schema.sql, run on startup (`spring.sql.init.mode=always`, everything `IF NOT EXISTS`). jjwt for tokens, Spring's `@Scheduled` for the expiry sweeper. Dependencies: web, data-jpa, security, validation, postgresql, jjwt.

If something else seems needed, ask first.

## Code structure

Standard Spring Boot layering. Each layer talks only to the one below it: controller -> service -> repository.

- `controller`: HTTP only. Takes the request, reads the user from the request attributes the auth filter set, calls a service, returns the response. Controllers hold no persistence logic.
- `service`: business logic and transaction boundaries. A service calls several repositories in the lock order from DESIGN.md, owns the `TransactionTemplate`, and runs the deadlock retry loop around it.
- `repository`: Spring Data JPA repositories for the entities. Keep custom persistence methods focused and named for their behavior (`lockSeatsForUpdate`, `markSeatsHeld`, `markExpiredHolds`). Repositories called inside a service transaction participate in that same transaction automatically.

Packages under `com.example.seats`: `controller`, `service`, `repository`, `model` (request/response DTOs as Java records, plus small domain types), `security` (JWT filter), `config` (`@ConfigurationProperties` for the `hold.*` settings), `exception` (`DomainException` and the advice), `scheduler` (the sweeper, which calls a service).

Conventions: constructor injection with `final` fields, DTOs as records, one class per responsibility.

## Rules that matter

- The seat decision is one guarded step inside the transaction (DESIGN.md has the SQL).
- Lock order is the same everywhere: idempotency row, user quota row, reservation row, then seat rows sorted by label. Each transaction takes the ones it needs, in that order. That's what keeps multi-seat requests from deadlocking. The sweeper takes its locks with `SKIP LOCKED` so it never waits.
- A hold whose time has passed counts as available everywhere (reserve, show counts), even before the sweeper has reset the row. DESIGN.md has the exact condition.
- Isolation stays READ COMMITTED. Guarded updates plus row locks are enough.
- Declined requests are 4xx. Everything goes through the `@RestControllerAdvice`.
- The user comes from the token. A `user_id` in the body is ignored.
- Money is `long` paise.
- Time comparisons use the database clock (`now()`), never the app's.
- Config comes from `application.properties`, with env var overrides.

## How to work

Small commits, one step each, using the messages in TASKS.md. Go through TASKS.md in order and stop after each section so I can try it in Postman.

I have to be able to explain the reserve, confirm, cancel and sweeper statements line by line in the interview. Keep them short, comment the lock order, and say what you did when you write them.

Before calling a task done, run the check listed under it. When you generate something sizeable, add a line to docs/AI_LOG.md (what I asked for, and whether I told you exactly what to write or made the design call myself). If something you wrote turned out wrong and got fixed, put that in too.

## Commands

```
docker run --name seats-db -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=seats -p 5432:5432 -d postgres:16   # local postgres
./mvnw spring-boot:run     # run the app against it
./mvnw test                # tests
```

## Done means

- Every request in the Postman scenarios (docs/TESTING.md) returns what the spec says.
- A hold that lapses goes back to available and can be held by someone else.
