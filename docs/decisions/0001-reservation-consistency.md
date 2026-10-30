# ADR 0001: Keep stock accounting and retry identity in one PostgreSQL transaction

**Status:** Accepted · **Date:** 2026-10-30

## Context

Two requests can read the same available stock before either writes. A counter constrained below on-hand can still disagree with allocation rows after a lost update. A retry after a lost HTTP response must also distinguish a committed order from a new request. The project has a small fixed batch set, one warehouse, one SKU per reservation, and no external side effect during allocation.

## Decision

Use `READ_COMMITTED`, a unique external request ID in `reservation`, and pessimistic row locks. Claim the ID with `INSERT ... ON CONFLICT DO NOTHING RETURNING` before touching stock. Lock eligible batches in ascending last-usable date and batch ID, calculate a complete allocation, and commit the claim, allocations, and counter changes together.

The JDBC claim participates in the same transaction as managed Hibernate entities. No extra idempotency table, distributed transaction, process-local lock, or cache is needed. Failed creations roll back their claim: the same ID can succeed after stock becomes available. Successful records remain durable, including after release.

A duplicate insertion waits for the competing transaction. After `DO NOTHING`, read the winner in a separate statement so `READ_COMMITTED` obtains a fresh snapshot; the row might not have been visible to the insertion's original snapshot. If the first transaction rolls back, the waiter may insert instead. Compare SKU, quantity, and date exactly. Recognize replay before the time-dependent date check, preserving retries after midnight and long after the required date.

Release locks its reservation before its batches and subtracts the original allocation quantities exactly once. Creation and release use the same batch order; neither requests another reservation lock while holding batch locks. Include fully reserved eligible rows in creation's query so a waiter observes a release's updated counter. Read batch state only after acquiring its lock.

Batch identity, eligibility fields, and ordering fields are immutable in this scope. This matters: an ordered locking query is not a general solution to concurrently inserting stock or changing its ordering/eligibility. Such features require revisiting the protocol.

## Alternatives and consequences

| Option | Consequence |
|---|---|
| Pessimistic locks, selected | Small allocation sets are easy to reason about; overlapping requests wait. Per-SKU independence remains. |
| Optimistic `@Version` | Requires versioned counters and bounded retries of the **whole** claim/read/plan/write transaction after conflicts. Multi-batch plans must be recomputed. |
| `SERIALIZABLE` | Provides broader anomaly detection but still aborts transactions under contention and requires whole-operation retry. Unnecessary for the fixed-inventory boundary. |
| JVM synchronization / in-memory IDs | Does not coordinate other processes or survive restart. |
| Independent request-ID transaction | Can leave a successful-looking claim after failed allocation; would require a separate recovery/state protocol. |

Stock contention is an accepted throughput tradeoff. Timeouts bound individual waits and statements, not total HTTP latency. A caller cannot infer commit outcome from a broken connection; retrying the same identity resolves that uncertainty. There is no claim of strict fairness or high throughput.

The unique constraint, row checks, and foreign keys prevent local invalid states. Application locks/transactions enforce cross-row accounting; reconciliation SQL and integration tests verify it. We deliberately retain allocation rows after release to explain what happened.

## Evidence

- [Contention tests](../../src/test/java/io/github/fullstacknick/stockreservation/ReservationConcurrencyIT.java) observe PostgreSQL blocking and assert final committed accounting.
- [Rollback tests](../../src/test/java/io/github/fullstacknick/stockreservation/ReservationRollbackIT.java) throw after real flushes and verify both JDBC/JPA changes disappear.
- [Restart test](../../src/test/java/io/github/fullstacknick/stockreservation/ReservationRestartIT.java) preserves one database outside two application contexts.
- [Incident exercise](../incidents/0001-lost-update.md) demonstrates the unsafe alternative and lock-removal regression.

PostgreSQL documents the relevant [Read Committed conflict behavior](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED) and [row-level locking](https://www.postgresql.org/docs/18/explicit-locking.html#LOCKING-ROWS).
