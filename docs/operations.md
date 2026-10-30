# Local operation and diagnosis

## Configuration

The demo uses PostgreSQL database `stock_reservation`, username `stock_reservation`, and password `local-demo-only`. These are public demo values. Both host ports bind to `127.0.0.1`. The database image is pinned by digest; its PostgreSQL 18 volume is mounted at `/var/lib/postgresql`.

The README defaults work without an `.env`. To use different ports, set variables in the shell that starts both Compose and Java:

```powershell
$env:DB_PORT = '5433'
$env:APP_PORT = '8081'
docker compose up -d --wait db
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=demo'
# In another terminal:
pwsh -File scripts/demo.ps1 -BaseUrl http://127.0.0.1:8081
```

```bash
export DB_PORT=5433 APP_PORT=8081
docker compose up -d --wait db
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

The same rule applies to `DB_USERNAME` and `DB_PASSWORD`. Compose can read an `.env`; the host Java process cannot. Initial database credentials are applied only when initializing an empty volume. Changing environment values does not change users/passwords inside an existing volume.

For normal mode, omit `--spring.profiles.active=demo` and supply `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` for a **separate database**. Flyway creates the schema; it does not insert inventory in normal mode. Supply fictional batch fixtures through SQL before the scenario, with no concurrent inventory edits. The application uses the system clock in `warehouse.zone`.

Keep demo and normal-mode databases separate because the demo has an additional Flyway migration location. Demo seeds use `ON CONFLICT DO NOTHING`; they do not reset stock or replace existing batches at restart.

Other Spring command-line settings include:

```text
--warehouse.zone=Europe/Berlin
--reservation.lock-timeout=3s
--reservation.statement-timeout=10s
--reservation.transaction-timeout-seconds=15
--spring.datasource.hikari.maximum-pool-size=10
--spring.datasource.hikari.connection-timeout=5000
```

Lock and statement limits bound individual database waits, not total HTTP duration. A `503` response includes `Retry-After: 1`; use bounded backoff and retry creation with the same external ID or release with the same reservation ID. A network disconnect leaves commit outcome uncertain; the same retry identity resolves it safely.

## Inspect committed state

Run the invariant check after creating/releasing stock. A healthy database returns **zero rows**:

```powershell
Get-Content docs/sql/check-invariants.sql -Raw | docker compose exec -T db psql -U stock_reservation -d stock_reservation -v ON_ERROR_STOP=1
Get-Content docs/sql/inspect-reservation.sql -Raw | docker compose exec -T db psql -U stock_reservation -d stock_reservation -v ON_ERROR_STOP=1 -v external_request_id=ORDER-1042-L1
Get-Content docs/sql/inspect-locks.sql -Raw | docker compose exec -T db psql -U stock_reservation -d stock_reservation -v ON_ERROR_STOP=1
```

```bash
docker compose exec -T db psql -U stock_reservation -d stock_reservation -v ON_ERROR_STOP=1 < docs/sql/check-invariants.sql
docker compose exec -T db psql -U stock_reservation -d stock_reservation -v ON_ERROR_STOP=1 -v external_request_id=ORDER-1042-L1 < docs/sql/inspect-reservation.sql
docker compose exec -T db psql -U stock_reservation -d stock_reservation -v ON_ERROR_STOP=1 < docs/sql/inspect-locks.sql
```

`inspect-reservation.sql` explains original allocations and current counters. `inspect-locks.sql` identifies blocked backend PIDs and wait events. `check-invariants.sql` finds accounting, eligibility, quantity, and state inconsistencies; it intentionally does not repair them. Do not manually adjust reserved counters to hide a discrepancy.

## Logs

Each HTTP completion includes correlation ID, matched route, status, duration, and validated business identifiers when known. The controller records successful business outcomes after the transactional proxy returns. Request bodies, raw rejected values, credentials, and SQL bind values are not logged by the application. Unexpected failures retain server-side stack traces but return safe problem details to the caller.

Enable optional JSON console output using [Spring Boot's structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured):

```text
java -jar target/stock-reservation-api-1.0.0.jar --spring.profiles.active=demo --logging.structured.format.console=ecs
```

For a short local SQL investigation, add `--logging.level.org.hibernate.SQL=DEBUG`. Keep bind-value logging off. Real logging timestamps and monotonic durations remain independent of the fixed demo clock.

## Troubleshooting and shutdown

| Symptom | Check |
|---|---|
| Cannot find Java / unsupported release | `java -version` and `javac -version` should both be Java 21; check `JAVA_HOME`. |
| Testcontainers cannot find Docker | Start the Linux Docker engine and verify `docker info`. The full suite deliberately fails rather than skipping database tests. |
| Port already occupied | Set matching `APP_PORT` / `DB_PORT` shell variables as above; do not stop unrelated services. |
| Database connection refused | `docker compose ps` and `docker compose logs db`; wait for the database to become healthy. |
| Authentication fails after changing `.env` | Existing volume credentials remain unchanged. Restore matching values or explicitly reset disposable demo data. |
| Demo receives insufficient stock | Release reservations created manually. The script never resets other reservations. |
| New request's required date is rejected | Start the `demo` profile for fixed examples; normal mode uses the real warehouse date. |
| Same ID still returns `RELEASED` | Expected: successful IDs are permanent. Use a new ID for a new reservation. |
| Maven cannot resolve dependencies | Check access to Maven Central and any required proxy configuration. No private repository credentials are required. |

Stop the host application with Ctrl+C. `docker compose down` removes the container/network and preserves the named volume. Starting it again and restarting the app preserves request IDs, allocation history, and stock counters.

**Intentional destructive reset of this project's demo data only:** stop the app, run `docker compose down --volumes`, then `docker compose up -d --wait db` and restart the demo app. This erases every demo reservation. There is no API reset endpoint and no global Docker cleanup command in this workflow.
