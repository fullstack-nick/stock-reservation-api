# Exercise: two orders reserve eight cases from five

**Deliberately reproduced exercise, 30 October 2026.** This is a test incident, not a claim of a real operational outage. All reproduction uses disposable Testcontainers PostgreSQL databases.

## Symptom and minimal fixture

Batch 301 contains five `APPLE-CASE` cases, last usable on 2026-11-10, initially reserved zero. Batch 302 is ineligible for the required date 2026-11-02. Two different external request IDs each ask for four cases.

The unsafe implementation reads `reserved = 0` in both transactions, creates two four-case allocations, and writes `reserved = 4` twice. Both return `201`. The row still satisfies `0 <= reserved <= on_hand`, but active allocations total eight. The reconciliation query detects the missing four cases in the counter.

## Reproduce safely

The checked-in [UnsafeAllocationIncidentIT](../../src/test/java/io/github/fullstacknick/stockreservation/UnsafeAllocationIncidentIT.java) replaces only its test-context stock repository read with an unlocked query. A latch waits until both transactions have read the original value before either proceeds. Real HTTP, service transactions, Hibernate writes, and PostgreSQL are retained. The test asserts the defect, then cleans its isolated fixture. There is no unsafe runtime setting or HTTP endpoint.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dit.test=UnsafeAllocationIncidentIT' verify
```

POSIX uses `./mvnw` with the same arguments. This command **passes when it reproduces and detects the intentionally introduced defect**. Correct application behavior is covered separately by `ReservationConcurrencyIT`.

## Captured evidence

During the temporary local `exercise/unsafe-allocation` branch exercise, the production eligible-stock query also had `FOR UPDATE OF b` removed. The command ran the normal competition regression and the coordinated incident fixture together:

```text
./mvnw --batch-mode --no-transfer-progress -Dtest=AllocationPlannerTest '-Dit.test=ReservationConcurrencyIT#competingRequestsCannotReserveEightOfFiveCases,UnsafeAllocationIncidentIT' verify
```

Actual completion events at `2026-10-30T14:47:25.637+01:00` are excerpted below; unrelated logger/thread fields are omitted:

```text
correlationId="844bbacf-4052-463f-a120-a180ceb6d2eb" status="201"
externalRequestId="INCIDENT-A" reservationId="8c809e33-803f-4d4c-bb26-823909b7f618"
sku="APPLE-CASE" quantity="4" outcome="CREATED"

correlationId="33bad70d-4e42-4013-8244-a60752f78812" status="201"
externalRequestId="INCIDENT-B" reservationId="813b80ef-d3b8-401e-b8b7-2e0e04eb0b0e"
sku="APPLE-CASE" quantity="4" outcome="CREATED"

INCIDENT EVIDENCE: HTTP statuses=201,201; batch=301; onHand=5; reserved=4;
activeAllocations=8; violations=[{violation=batch_reserved, entity_id=301, expected=8, actual=4}]
```

The fixture read committed state after both responses: two reservations, two allocations of four for batch 301, and a reserved counter of four. Its assertions require those exact values. IDs vary on later runs.

The **normal regression failed** with this actual assertion output:

```text
ReservationConcurrencyIT.competingRequestsCannotReserveEightOfFiveCases
Expecting actual:
  [201, 201]
to contain exactly in any order:
  [201, 409]
Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
BUILD FAILURE
```

This distinguishes the regression from the fixture: the fixture proves the unsafe interleaving and accounting discrepancy; the unchanged regression rejects the unsafe production query. The exercise change was reverted and the working branch returned to `main`. No unsafe production commit or branch is published.

## Cause and correction

The unlocked read gives each persistence context the same stale counter. Each computes an absolute new value of four. PostgreSQL serializes the later `UPDATE`s, but this alone cannot repair stale planning: the second write overwrites four with four. A check constraint sees a locally valid value and misses the cross-row discrepancy.

The correct query locks eligible batches **before reading/planning**, in `(last_usable_on, id)` order, including fully reserved rows. The second caller waits and sees the first caller's committed `reserved = 4`, leaving only one case. It returns `409 INSUFFICIENT_STOCK` and rolls back its request claim. The final counter and active allocation total both equal four. Release follows the same batch order after locking its reservation.

After restoring the lock, the focused regression passed. The full suite also verifies duplicate identities, rollback while a duplicate waits, releases, overlapping date eligibility, and independent SKUs. See [verification evidence](../verification.md) and the [consistency ADR](../decisions/0001-reservation-consistency.md).

## Übergabe auf Deutsch

**Symptom:** Zwei Bestellungen reservierten jeweils vier Kartons, obwohl nur fünf verfügbar waren. Beide Antworten waren erfolgreich; der Reservierungszähler zeigte vier, die aktiven Zuordnungen insgesamt acht.

**Ursache:** Beide Transaktionen lasen denselben alten Zähler ohne Zeilensperre. Die zweite Aktualisierung überschrieb den Zähler mit demselben Wert. Die lokale Mengenprüfung erkannte die Abweichung zwischen den Tabellen nicht.

**Korrektur:** Geeignete Chargen vor der Planung mit `FOR UPDATE` in einheitlicher Reihenfolge sperren. Anforderungs-ID, Zuordnungen und Zähler gemeinsam in einer Transaktion speichern.

**Nachweis:** Ohne Sperre schlug der Regressionstest mit zwei `201`-Antworten fehl. Mit Sperre entstehen eine `201`- und eine `409`-Antwort; Zähler und aktive Zuordnungen stimmen überein. Die SQL-Abgleichprüfung ergänzt die HTTP-Prüfung.
