# ADR 0014: Platform: independent Java service, PostgreSQL 18 with jOOQ, database roles, append-only enforcement, RLS, durability targets

Status: Proposed · Date: 2026-11-15 · Supersedes 0002 · Amends 0003, 0007, 0013 · Relates to 0001, 0012

Replaces earlier drafts: old 0033, old 0043; old 0028 (rejected, see Alternatives).

## Context
Review `docs/architecture/ledger-architecture-review.md` §5, §7.1, §7.3, §15, §17, §19. Review §17 compared
three options: A, keep the ledger inside Sarv's .NET modular monolith; B, extract it as an independent .NET
service; C, build it as an independent Java 25 service. It found no technical reason for either platform to be
inherently more correct or faster; option C carries the highest migration risk (full rewrite) and fits ADR 0001
and 0012 (Kimia as an independent Apache-2.0 product). The repositories hold no evidence about team size or skills.

**Owner decision (2026-11-15), service:** Java 25 and Spring Boot 4.x. Ledger is an independently deployed Java
service separate from Sarv's .NET monolith. This is an organizational and architectural choice, not a claim that
Java is faster or safer. Migration cost and operational responsibility are accepted.

**Owner decisions (2026-11-15), database:** Oracle is no longer a target; PostgreSQL 18 is the baseline;
old 0028 is rejected and ADR 0002 superseded. **jOOQ for v1 persistence** with PostgreSQL and JDBC: type-safe DSL for
ordinary queries; explicit SQL allowed for `FOR UPDATE`, `UPDATE … RETURNING`, CTEs and advisory locks; all
jOOQ and SQL only in `ledger-persistence-postgres`; classes generated from the migrated schema; compatible
versions pinned and verified in CI; no Hibernate/JPA on the financial write path; jOOQ may serve reporting
queries; a separate analytics database only if measured; no second persistence framework without a concrete
requirement. (The first draft left jOOQ versus `JdbcClient` open; the owner decided jOOQ the same day. The
licence and version risk is kept as a risk with a CI check.)

Facts and labels from the review:
- The review §7.3 DDL ran on `postgres:18` (18.7, image digest `sha256:b9f6b8ff…`) with `ON_ERROR_STOP=1`:
  32 tables, 65 non-internal triggers, 86 policies, 67 indexes and 35 smoke checks (review §2.2). After the outbox
  changes of 2026-11-15 (cleanup role, quarantine table, aggregate columns) the same DDL has 33 tables, 71 triggers,
  86 policies and 72 indexes; the 35 checks still pass, and 50 further checks behaved as expected (15 retention
  checks, 35 for ordering key, aggregate sequence, quarantine and the metadata rules). Single-session checks on
  `postgres:18` (18.7); no concurrency, load, Flyway or jOOQ.
- PostgreSQL row locks (`FOR UPDATE`, `FOR KEY SHARE`) need the `UPDATE` privilege (PostgreSQL 18 `SELECT`
  documentation, read by the review).
- jOOQ OSS (jooq.org/download, read 2026-11-14): Apache-2.0; "No commercial databases"; "PostgreSQL (latest
  versions)". jOOQ 3.21.9 is in the Spring Boot 4.1.2 BOM and needs Java 21+ (verified). PostgreSQL 18 support
  in 3.21.9: **unverified**. Flyway 12.4.0 (BOM) with PostgreSQL 18: **unverified**. pgJDBC 42.7.14: PostgreSQL 18
  compatibility unverified; licence BSD-2-Clause per the earlier review, not re-checked.

## Decision

### D1. Independent Java service (old 0043)
1. Kimia Ledger is built and deployed as its own Java 25 / Spring Boot 4.x service (ADR 0001 stays), with its own
   PostgreSQL 18 database (D2). Sarv's ledger module is not extended for the new model.
2. Integration with Sarv and other services is only through the published contracts (gRPC, REST, Kafka;
   ADR 0012, ADR 0024). No shared database, no shared process.
3. Sarv stays the system of record until each flow is cut over (review §18). For each flow and account there is
   exactly one system of record at any time; no dual write.
4. The team owning Kimia takes JVM operations: runtime, patching, monitoring and on-call for the service.

### D2. PostgreSQL 18, jOOQ and Flyway (old 0033)
1. **PostgreSQL 18** is the only database. Images pinned by tag and digest. Module
   `ledger-persistence-postgres` (`com.kimialedger.persistence.postgres`) holds all SQL, jOOQ, Flyway
   migrations and the jOOQ code generation.
2. **jOOQ OSS over JDBC.** DSL for ordinary queries. Explicit SQL for locking, `UPDATE … RETURNING`, CTEs and
   advisory locks, all in one lock-and-write class with integration tests. jOOQ classes are generated from the
   schema after Flyway runs on Testcontainers. Versions of PostgreSQL, pgJDBC, jOOQ and Flyway are pinned
   exactly; CI runs the integration suite on the pinned set. No Hibernate/JPA. jOOQ may serve reporting
   queries on the same database; a separate analytics store needs a measured reason and its own ADR.
3. **Flyway** runs as `ledger_migrator`, which does `SET ROLE ledger_owner`.
4. **Portable rules kept from old 0028:** ids from the application (UUIDv7), domain times from the `Clock` port
   (ADR 0026), `READ COMMITTED` plus explicit locks, keyset pagination. Identity columns (outbox) and
   `RETURNING` are allowed.

### D3. Database roles and append-only enforcement (old 0033)
1. **Roles** (review §7.3 section 0 and 13, §15): `ledger_owner` (NOLOGIN, owns schema), `ledger_migrator`,
   `ledger_app` (append-only tables `SELECT, INSERT`; state tables `SELECT, INSERT, UPDATE`; outbox `INSERT`; no
   `DELETE` or `TRUNCATE`), `ledger_relay` (publish path: outbox `SELECT` and `UPDATE` of three columns and `UPDATE`
   of `first_failed_at`, no `DELETE`; reads `outbox_quarantine`), `ledger_cleanup` (cleanup path: `SELECT, DELETE` on
   `outbox_events` and `inbox_events`; a trigger refuses `DELETE` of an unpublished outbox row, ADR 0024 D7),
   `ledger_readonly` (`SELECT` on all), `ledger_operator` (tenant registry, `tenant_settings`, `hold_policies`),
   `ledger_repair` (NOLOGIN, column `UPDATE` on positions plus `projection_repairs`, and `INSERT` of outbox quarantine
   records, granted per incident, ADR 0025 D3, ADR 0024 D7.12). Credentials from Vault or the environment.
2. **Append-only enforcement, three layers**: no grant; triggers that refuse `UPDATE`, `DELETE`, `TRUNCATE` on
   append-only tables (owner included while enabled) and `DELETE`, `TRUNCATE` on state tables; identity columns
   immutable by trigger. Audit of DDL and role changes (for example pgaudit; licence and version unverified).
   Superuser access is break-glass. Tampering is detected by reconciliation (ADR 0025 D3). On the delivery tables, a
   trigger also refuses any change of `published_at` once set, `TRUNCATE` is refused on `outbox_events` and
   `inbox_events`, and only `ledger_cleanup` may delete old published or old inbox rows (ADR 0024 D7).
   `outbox_quarantine` is append-only (no UPDATE, DELETE or TRUNCATE for anyone, owner included) and accepts a record
   only for an unpublished event.
3. **No `ON DELETE CASCADE`.** Composite tenant foreign keys, `ON DELETE RESTRICT` (ADR 0020 D4).

### D4. Row-level security as the second layer (old 0033)
RLS is the second layer, not a replacement for explicit `tenant_id` predicates (ADR 0020 D4): `ENABLE` and
`FORCE` on every tenant table; `tenant_isolation` for `ledger_app` and `ledger_repair` keyed on
`set_config('app.tenant_id', …, true)` per transaction; fail closed when unset; owner, readonly and operator
policies as in the DDL. Outbox and inbox carry no RLS.

### D5. Durability and recovery targets (old 0033)
**Owner decision (2026-11-15):** RPO = 0 for acknowledged financial commits within the defined synchronous HA
failure domain (synchronous replication plus failover design). RTO = 15 minutes as a target, not verified;
validated by restore and failover drills. Cross-region DR RPO/RTO is not decided.

1. **Synchronous replication** inside the HA failure domain: `synchronous_commit = on` with at least one
   synchronous standby (`synchronous_standby_names`, quorum form); the service acknowledges only after commit.
2. **Failover** promotes only a synchronous standby; asynchronous replicas are never promoted inside the HA
   domain. Tool chosen in the DR design (licence and version to verify).
3. **RTO 15 min** stays a target until a timed failover drill and a timed restore (WAL archiving and PITR) meet it.
4. If no synchronous standby is available, writes block rather than silently degrading RPO; the alert and the
   operator decision are in the runbook.
5. Kafka cluster settings follow this domain (ADR 0024 D5).

## Consequences
- Full rewrite of the ledger domain; Sarv's tests, load scenarios (`distributed`, `hot`, `fee`) and operational
  lessons are reused as test cases, not as code. Two runtime stacks (.NET and JVM) run in parallel during
  migration. The review's comparison table (§17) stays as the rationale; it does not reopen the choice.
- Oracle Immutable or Blockchain tables are lost; grants, triggers and audit are weaker (owner or superuser can
  bypass). Accepted with the owner's decision; detection relies on reconciliation.
- `ledger_app` holds `UPDATE` on money-bearing state; the threat "projection changed without a posting" is
  covered by chain checks (ADR 0017, ADR 0025).
- `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`: PostgreSQL 18, `ledger-persistence-postgres`, jOOQ allowed only
  there, `postgres:18` image. `ArchitectureTest` (out of this change): `org.jooq..` and `org.postgresql..` only in
  `persistence.postgres..`.

## Risks
- **jOOQ OSS supports only "latest" PostgreSQL versions.** A long-lived PostgreSQL 18 deployment can fall out of
  the OSS support window while jOOQ moves on. Mitigation: CI job that runs codegen and the integration suite on
  the pinned PostgreSQL and jOOQ pair; a PostgreSQL upgrade policy that keeps production within the OSS window;
  re-read the jOOQ licence page at every jOOQ upgrade. If the window cannot be kept, a commercial licence or a
  new ADR is needed.

## Needs benchmark or human verification
- Spike: PostgreSQL 18, Flyway 12.4.0, jOOQ 3.21.9, pgJDBC 42.7.14 (unverified combination).
- Non-transactional Flyway migration for `CREATE INDEX CONCURRENTLY` in 12.x: unverified.
- RLS and trigger cost on the write path: benchmark (ADR 0017, capacity section).

## Open items
- Team staffing and on-call coverage for a JVM service (owner: engineering management; assumption: the Kimia
  team covers it; needs: a staffing plan before production).
- Cross-region DR RPO/RTO (owner: business and operations; assumption: none promised; needs: region design and
  measured replication lag).
- Legal retention periods (owner: legal/DPO; assumption: nothing financial is deleted until decided).

## Alternatives rejected
- **Oracle only in v1 (old 0028, and ADR 0002).** One database (Oracle), the Oracle JDBC driver as a recorded
  licence exception kept out of published images until a human signed off its terms, minimum version a human
  decision, Oracle immutable tables for the journal decided before production. Lost because the owner decided on
  2026-11-15 that Oracle is no longer a target. Its cost-free portability rules survive in D2.4.
- **A: ledger inside the .NET monolith.** Lowest migration risk, but shares process and database with Sarv and
  ties the open-source product to Sarv.
- **B: independent .NET service.** Viable; not chosen by the owner for organizational reasons.
- **Plain `JdbcClient` only.** No compile-time schema check; owner chose jOOQ.
- **JPA/Hibernate on the write path.** Hides locks and implicit updates.
- **RLS as the only tenant control.** One misconfigured setting would be the only barrier.
