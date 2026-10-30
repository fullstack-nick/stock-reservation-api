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

## Fresh checkout and process checks

A new clone of the public repository passed the complete `verify` command. The documented Compose startup created an empty named volume, applied schema and demo migrations, and exposed PostgreSQL on loopback. The packaged JAR started with health `UP`.

Two consecutive `pwsh -File scripts/demo.ps1` runs passed. Together they left four released reservations, eight seed batches, total reserved quantity zero, and zero rows from `check-invariants.sql`. Each run demonstrated 6+3 allocation and competing apple requests returning one `201` and one `409`.

A separate nine-case reservation remained active while the JAR process was stopped. Starting a new application process with the README's `spring-boot:run` command preserved its UUID, creation time, allocations, and counters 6 and 3. Replay returned `200`, GET agreed, the seed count stayed eight, and releasing it restored valid accounting. Both application and database listeners were confirmed on `127.0.0.1`.

The restart integration test also exercises the documented ECS JSON formatter and parses all five completion events with duplicate-key detection. It checks correlation IDs, persisted business IDs, outcomes, HTTP statuses, and absence of formatter failures. Text and JSON logging share business identifiers through MDC without duplicate fields.

## Public CI and documentation

The [public Verify workflow](https://github.com/fullstack-nick/stock-reservation-api/actions/workflows/ci.yml) runs the same complete suite on Ubuntu 24.04 and Temurin 21. The [first successful run](https://github.com/fullstack-nick/stock-reservation-api/actions/runs/41067171816) ran 47 unit/slice and 45 integration tests and uploaded `verification-reports`. The README badge follows the current `main` result.

OpenAPI 3.1 structure and the documented response example were validated with `openapi-spec-validator` and JSON Schema tooling in a disposable local environment. Markdown file links, YAML syntax, PowerShell script syntax, executable wrapper mode, public repository visibility, `main` default branch, and GitHub's MIT license recognition were checked. These documentation tools are not application dependencies.

## Reproduce

Run full `verify` for acceptance, or the named classes for investigation:

```text
./mvnw '-Dit.test=ReservationConcurrencyIT' verify
./mvnw '-Dit.test=ReservationRollbackIT,ReservationRestartIT,ReservationTimeoutIT' verify
./mvnw '-Dit.test=UnsafeAllocationIncidentIT' verify
```

Use `.\mvnw.cmd` on Windows. These focused commands still run the unit/slice suite; they select only the specified integration classes. Surefire and Failsafe reports are under `target/`. The [Verify workflow](../.github/workflows/ci.yml) uploads those reports and coverage for seven days. No deployment workflow exists.
