# Architecture Decision Records

Accepted ADRs are never rewritten. To change one, add a new ADR that supersedes it and set the old status to
`Superseded by NNNN`. A Proposed ADR may be edited until the owner accepts it (the 0014–0026 set was consolidated this
way on 2026-11-15).

| ADR | Title | Status |
|---|---|---|
| [0001](0001-java-25-spring-boot-gradle.md) | Java 25 LTS, Spring Boot 4, Gradle Kotlin DSL multi-module build | Accepted |
| [0002](0002-oracle-primary-database-plain-jdbc.md) | Oracle as primary database, plain JDBC, Flyway, no jOOQ | Accepted; superseded by 0014 (Proposed) |
| [0003](0003-hexagonal-architecture.md) | Hexagonal architecture (ports and adapters) | Accepted |
| [0004](0004-contract-first-proto-grpc-json-transcoding.md) | Contract-first: proto is the source of truth, REST via transcoding | Accepted |
| [0005](0005-n-leg-journal-entries.md) | N-leg journal entries, per-currency balance, five account types | Accepted |
| [0006](0006-money-integer-minor-units.md) | Money as integer minor units plus ISO-4217 currency | Accepted |
| [0007](0007-append-only-journal.md) | Append-only journal; corrections are reversing entries | Accepted |
| [0008](0008-synchronous-atomic-write-path-transactional-outbox.md) | Synchronous atomic write path; transactional outbox | Accepted |
| [0009](0009-idempotency-keys.md) | Mandatory idempotency key on every write | Accepted |
| [0010](0010-multi-tenancy.md) | Multi-tenancy by tenant_id from day one | Accepted |
| [0011](0011-no-pii.md) | No personal data in the ledger | Accepted |
| [0012](0012-no-client-sdks.md) | No client SDKs; published contracts are the integration surface | Accepted |
| [0013](0013-security-baseline.md) | Security baseline | Accepted |
| [0014](0014-platform-java-postgresql-jooq.md) | Platform: independent Java service, PostgreSQL 18 with jOOQ, database roles, append-only enforcement, RLS, durability targets | Proposed |
| [0015](0015-money-and-assets.md) | Money and assets: NUMERIC(38,0) and BigInteger, per-asset immutable scale, decimal strings on the wire | Proposed |
| [0016](0016-journal-model.md) | Journal model: transactions, postings, effective time, reversals, refunds and adjustments | Proposed |
| [0017](0017-balance-projection-and-write-path.md) | Balance projection, write path, per-position order, lock order, timeouts and concurrency limits | Proposed |
| [0018](0018-idempotency.md) | Idempotency: one key, one outcome | Proposed |
| [0019](0019-api-contract.md) | API contract: REST conforms to the transcoding mapping, error contract, contract freeze and breaking baseline | Proposed |
| [0020](0020-identity-tenancy-and-authorization.md) | Identity, tenancy, authorization scopes, edge controls and tenant isolation in the database | Proposed |
| [0021](0021-ownership-wallets-and-buckets.md) | Ownership boundary, wallets and buckets, wallet states, system and exceptional operations, provisioning saga, payment-timeline boundary | Proposed |
| [0022](0022-external-account-sync.md) | External account state: cache, snapshots, lookup before BEGIN, fence, seal, overdraft policy | Proposed |
| [0023](0023-holds.md) | Native holds on bucket positions, versioned hold policies, append-only hold_events | Proposed |
| [0024](0024-events-outbox-inbox-kafka.md) | Events: transactional outbox and inbox on Kafka | Proposed |
| [0025](0025-fx-settlement-reconciliation.md) | FX execution, settlement, reconciliation, projection repair, incident freeze, recovery and retention | Proposed |
| [0026](0026-module-structure.md) | Module structure: transaction port and the ledger-contracts module | Proposed |

A partial change is recorded as `Amends NNNN` in the new ADR and `Amended by NNNN` at the top of the old one;
the old body stays as written and is historical where it conflicts. The status of an accepted ADR changes to
`Superseded by NNNN` only when the ADR that replaces it is accepted.

## Consolidation map (old draft number -> ADR)

On 2026-11-15 the 30 Proposed drafts 0014–0043 were merged into 0014–0026 before any of them was accepted, so the
draft numbers were reused. Commit messages and `docs/review/2026-11-14-architecture-review.md` cite the old
numbers; each new ADR names its drafts in a "Replaces earlier drafts" line, and superseded or rejected drafts are
recorded under its "Alternatives rejected". The old files are in git history.

| Old draft | Title of the draft | Now in |
|---|---|---|
| old 0014 | Per-account posting sequence; no tenant-wide journal order | 0017 D2 |
| old 0015 | REST is a hand-written adapter that conforms to the transcoding mapping | 0019 D1 |
| old 0016 | Entry model: effective time, server-built reversals, repeated accounts, input message | 0016 |
| old 0017 | Idempotency semantics: one key, one outcome | 0018 |
| old 0018 | Error contract, status mapping and correlation id | 0019 D2 |
| old 0019 | Money range, storage width, currency column and exponent (superseded) | 0015, Alternatives rejected |
| old 0020 | Write path with insert-only running balances (superseded) | 0017, Alternatives rejected |
| old 0021 | No events and no outbox in v1 (superseded) | 0024, Alternatives rejected |
| old 0022 | No native holds in v1; hold account (superseded) | 0023, Alternatives rejected |
| old 0023 | Service edge: tenant identity, TLS termination, rate limiting | 0020 D1–D3 |
| old 0024 | A separate scope to create accounts that may go negative | 0020 D5 and Alternatives rejected |
| old 0025 | Account lifecycle, lookup and caller-supplied fields (superseded) | 0021, Alternatives rejected |
| old 0026 | Timeouts, per-tenant concurrency cap and hot accounts | 0017 D7 |
| old 0027 | Tenant isolation in the database | 0020 D4 |
| old 0028 | Oracle only in v1 (rejected by the owner) | 0014, Alternatives rejected |
| old 0029 | Reconciliation, account freeze, recovery and retention | 0025 D3, D5 |
| old 0030 | Transaction port in ledger-application | 0026 D1 |
| old 0031 | ledger-contracts module | 0026 D2 |
| old 0032 | Contract freeze, breaking-change baseline and client tolerance | 0019 D3 |
| old 0033 | PostgreSQL 18 with jOOQ, database roles, append-only enforcement, RLS; durability targets | 0014 D2–D5 |
| old 0034 | Money as NUMERIC(38,0) and BigInteger, per-asset scale | 0015 |
| old 0035 | Ownership boundary, bucket-account mapping, provisioning saga | 0021 D1–D3 |
| old 0036 | Wallet and bucket lifecycle; system and exceptional operations | 0021 D4–D5 |
| old 0037 | External account sync, fence, seal; overdraft policy | 0022 |
| old 0038 | Transactional balance projection, write path, lock order; capacity method | 0017 (reversal in 0016 D3, port in 0026 D1) |
| old 0039 | Native holds | 0023 |
| old 0040 | Transactional outbox and inbox on Kafka | 0024 |
| old 0041 | FX execution, settlement, reconciliation; valuation | 0025 D1–D4 |
| old 0042 | Payment timeline boundary | 0021 D6 |
| old 0043 | Independent Java ledger service | 0014 D1 |

Template: `# ADR NNNN: Title`, `Status · Date`, then `## Context`, `## Decision` (sub-decisions `D1`…`Dn`),
`## Consequences`, `## Open items` and `## Alternatives rejected` where it matters.
