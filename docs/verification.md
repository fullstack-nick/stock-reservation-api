# Verification evidence

Verification date: **30 October 2026**. Commands use the checked-in Maven Wrapper; no host Maven or PostgreSQL installation is required.

## Application tests

The complete local command on Windows 11, Temurin Java 21, and Docker's Linux engine:

```text
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Surefire: planner, HTTP validation, failure classification | 47 | 0 | 0 | 0 |
| Failsafe: PostgreSQL, HTTP, contention, rollback, restart, incident | 45 | 0 | 0 | 0 |
| Total | 92 | 0 | 0 | 0 |

This is the application suite, not the earlier research compatibility probe. It uses the pinned PostgreSQL 18.6 image and the real Flyway migrations. The test clock and inventory are deterministic. `target/site/jacoco/index.html` is generated; coverage is supporting evidence, not the acceptance criterion.

Contention tests use separate HTTP calls/transactions and observe `pg_blocking_pids` before releasing deliberate holders. Rollback tests flush real writes before throwing. Fresh database reads check accounting and unchanged on-hand quantities after each integration scenario. Restart verification keeps the database outside both application contexts and checks current-state replay after release.

## Mutation and incident check

On a temporary local exercise branch, removing `FOR UPDATE OF b` caused `competingRequestsCannotReserveEightOfFiveCases` to fail: actual HTTP statuses `201,201`, expected `201,409`. The deliberately unsafe fixture independently verified two four-case allocations, counter four, and a reconciliation violation. See the [captured evidence](incidents/0001-lost-update.md).

After restoring the query and returning to `main`, the same focused regression passed with no failures/errors/skips. The unsafe behavior exists only in a Testcontainers fixture on the published branch.

## Reproduce

Run full `verify` for acceptance, or the named classes for investigation:

```text
./mvnw '-Dit.test=ReservationConcurrencyIT' verify
./mvnw '-Dit.test=ReservationRollbackIT,ReservationRestartIT,ReservationTimeoutIT' verify
./mvnw '-Dit.test=UnsafeAllocationIncidentIT' verify
```

Use `.\mvnw.cmd` on Windows. These focused commands still run the unit/slice suite; they select only the specified integration classes. Surefire and Failsafe reports are under `target/`. The [Verify workflow](../.github/workflows/ci.yml) uploads those reports and coverage for seven days. No deployment workflow exists.
