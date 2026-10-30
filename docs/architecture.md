# Architecture walkthrough

The application has one Maven module, one Spring process, and one PostgreSQL database. Packages group reservation and stock behavior; a small `support` package owns HTTP errors and request logging. There is no remote service inside the transaction.

## Creation

1. The correlation filter selects a UUID. Strict Jackson parsing and Bean Validation reject invalid shape, unknown properties, coercions, and noncanonical strings before calling the service.
2. The service proxy opens a `READ_COMMITTED` transaction. Transaction-local lock/statement timeouts are applied before claiming an identity.
3. JDBC inserts the reservation shell with `ON CONFLICT (external_request_id) DO NOTHING RETURNING id`. Its unique constraint coordinates duplicates, including requests for different SKUs.
4. A duplicate waits for the first transaction. If that transaction commits, a **subsequent SQL statement** loads its reservation, compares the original business input, and returns replay or conflict. If it rolls back, the waiting insertion can become the creator. No exception is caught to continue inside an aborted PostgreSQL transaction.
5. A new creator checks `requiredOn` against the captured warehouse date. Replays bypass this moving rule.
6. JPA loads eligible stock with `FOR UPDATE`, ordered by `(last_usable_on, id)`. Fully reserved rows are included: a waiting caller must be able to see stock restored by a release.
7. The pure planner returns the complete FEFO allocation or throws for insufficiency. Managed counters and allocation rows are flushed together. The reservation shell remains invisible outside the transaction until commit.
8. The service builds a DTO within the transaction. Its proxy commits before the controller sets the successful outcome. The response includes reservation history, never mutable availability counters.

`JpaTransactionManager` owns one datasource; `JdbcTemplate` joins its bound connection. The failure-after-flush tests demonstrate that the claim, Hibernate changes, and allocation inserts roll back together. Open Session in View is disabled.

## Release and inspection

Release first locks the reservation row, so two releases cannot both subtract its allocations. An already released row returns immediately. For an active row, a projection reads allocation quantities without loading batch entities, then the service locks allocated batches in the same date/ID order used by creation. It checks each counter before subtraction, changes status/time once, flushes, and retains allocation history.

GET uses a read-only transaction and an explicit allocation/batch fetch. An inspection racing with release may observe the state committed when its reservation read ran. It never reports an availability snapshot or promises an unchanged status across retries.

## Database guarantees and application invariants

The [migration](../src/main/resources/db/migration/V1__create_reservation_schema.sql) declares primary/foreign keys, unique external IDs, canonical strings, date bounds, positive quantities, valid states/timestamps, and `0 <= reserved <= on_hand`. Batch identity, SKU, date, and on-hand quantity stay fixed during a scenario.

Cross-row accounting is protected by the service transaction and ordered locks:

```text
batch.reserved = SUM(allocation.quantity for ACTIVE reservations on that batch)
reservation.quantity = SUM(its retained allocation.quantity), including after release
allocation.batch.sku = reservation.sku
allocation.batch.last_usable_on >= reservation.required_on
```

The [diagnostic query](sql/check-invariants.sql) reports violations; it is not a repair job or a trigger. The deliberately unsafe fixture demonstrates why row checks alone cannot enforce the first equality.

## Time, errors, and observability

The clock is injected. System mode derives today in `warehouse.zone`; demo mode fixes the instant at `2026-11-01T09:00:00Z`. Dates use `LocalDate`; timestamps use UTC `Instant` truncated to PostgreSQL microseconds. HTTP duration uses `System.nanoTime`, unaffected by business time.

Expected validation/conflict errors have stable problem codes and no stack traces in responses. Temporary database failures return `503` with `Retry-After: 1`; callers retry with the same identity. Unanticipated failures return safe `500` details and retain the exception in server logs. There is no internal retry loop.

The filter emits one completion event with HTTP status, matched route, duration, correlation ID, and available validated business identifiers. MDC is cleared in `finally`. Standard framework errors and health requests may lack business identifiers; raw rejected input and request bodies are not logged.

## Boundaries

Pessimistic locking fits a small, fixed inventory set. Overlapping batch sets serialize; independent SKUs can progress. New inventory writers, mutable ordering columns, cancellation expiry, authentication, and public hosting would require new design work. See [ADR 0001](decisions/0001-reservation-consistency.md) for alternatives and tradeoffs.
