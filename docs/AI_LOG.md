# AI log

Running notes for the AI usage section of the write-up. Add a line whenever AI generates something sizeable.

For each entry: what I asked for, and whether I told it exactly what to write (directed) or made the design call myself (decided). If its output was wrong and I changed it, note that too, because those are the most useful lines.

Format: `date - area - directed / decided / corrected - what happened`

2026-10-04 - seat reservation backend - decided - Implemented and validated the business flow from docs/SPEC.md and docs/DESIGN.md: admin-only show creation, JWT auth, hold creation with idempotent replay, owner-only confirm/cancel, and show-state counts with expired holds counted as available. Verified against the local Postgres app using the working reserve/confirm/cancel and replay paths.
2026-10-04 - persistence migration - directed - Replaced JdbcTemplate repositories with Spring Data JPA/Hibernate entities and repositories, retaining PostgreSQL native statements where needed for idempotency inserts, row locks, expiry logic, and guarded updates. Updated dependency/configuration and persistence docs; Maven tests and live PostgreSQL checks passed for reserve/replay, conflicts, quotas, multi-seat atomicity, confirm/cancel/rebook, and expiry.
2026-10-04 - metrics and observability - directed - Implemented reservation counters, a database-derived available-seat gauge per show, database-aware readiness and process liveness endpoints, JSON request logs with propagated X-Request-ID, and local Docker/Compose setup and documentation. Added a Render Blueprint; live deployment and requested screen recording await account access.
2026-10-04 - Render burst handling - decided - Fixed a cold show-cache miss stampede so a burst reads a show's configuration from Postgres once, and corrected the misspelled Tomcat max-connections property after reviewing the 20,000-request failure diagnosis.
2026-10-04 - concurrency harness - directed - Converted the Node correctness burst harness into a Bash script using curl, jq, and bounded xargs workers, preserving token setup, hot-seat load, live reconciliation sampling, idempotency, quota, identity/cancel checks, result logs, and failure status.
