# Architecture Decision Records

One file per decision, numbered, never rewritten. To change a decision, add a new ADR that supersedes the old
one and set the old status to `Superseded by NNNN`.

| ADR | Title |
|---|---|
| [0001](0001-java-25-spring-boot-gradle.md) | Java 25 LTS, Spring Boot 4, Gradle Kotlin DSL multi-module build |
| [0002](0002-oracle-primary-database-plain-jdbc.md) | Oracle as primary database, plain JDBC, Flyway, no jOOQ |
| [0003](0003-hexagonal-architecture.md) | Hexagonal architecture (ports and adapters) |
| [0004](0004-contract-first-proto-grpc-json-transcoding.md) | Contract-first: proto is the source of truth, REST via transcoding |
| [0005](0005-n-leg-journal-entries.md) | N-leg journal entries, per-currency balance, five account types |
| [0006](0006-money-integer-minor-units.md) | Money as integer minor units plus ISO-4217 currency |
| [0007](0007-append-only-journal.md) | Append-only journal; corrections are reversing entries |
| [0008](0008-synchronous-atomic-write-path-transactional-outbox.md) | Synchronous atomic write path; transactional outbox |
| [0009](0009-idempotency-keys.md) | Mandatory idempotency key on every write |
| [0010](0010-multi-tenancy.md) | Multi-tenancy by tenant_id from day one |
| [0011](0011-no-pii.md) | No personal data in the ledger |
| [0012](0012-no-client-sdks.md) | No client SDKs; published contracts are the integration surface |
| [0013](0013-security-baseline.md) | Security baseline |
| [0014](0014-per-account-posting-sequence.md) | Per-account posting sequence; no tenant-wide journal order (Proposed) |
| [0015](0015-rest-conforms-to-transcoding-mapping.md) | REST is a hand-written adapter that conforms to the transcoding mapping (Proposed) |
| [0016](0016-entry-model.md) | Entry model: effective time, server-built reversals, repeated accounts, input message (Proposed) |
| [0017](0017-idempotency-semantics.md) | Idempotency semantics: one key, one outcome (Proposed) |
| [0018](0018-error-contract.md) | Error contract, status mapping and correlation id (Proposed) |
| [0019](0019-money-range-and-currency-storage.md) | Money range, storage width, currency column and exponent (Proposed) |
| [0020](0020-write-path-insert-only-balances.md) | Write path with insert-only running balances (Proposed) |
| [0021](0021-no-events-in-v1.md) | No events and no outbox in v1 (Proposed) |
| [0022](0022-no-holds-in-v1.md) | No native holds in v1; holds are modelled with a hold account (Proposed) |
| [0023](0023-service-edge-identity-tls-rate-limits.md) | Service edge: tenant identity, TLS termination, rate limiting (Proposed) |
| [0024](0024-overdraft-scope.md) | A separate scope to create accounts that may go negative (Proposed) |
| [0025](0025-account-lifecycle-and-lookup.md) | Account lifecycle, lookup and caller-supplied fields (Proposed) |
| [0026](0026-timeouts-and-concurrency-limits.md) | Timeouts, per-tenant concurrency cap and hot accounts (Proposed) |
| [0027](0027-tenant-isolation-in-the-database.md) | Tenant isolation in the database: composite keys now, row policies later (Proposed) |
| [0028](0028-oracle-only-in-v1.md) | Oracle only in v1; driver licence exception; immutable tables before production (Proposed) |
| [0029](0029-reconciliation-freeze-recovery.md) | Reconciliation, account freeze, recovery and retention (Proposed) |
| [0030](0030-transaction-port.md) | Transaction port in ledger-application (Proposed) |
| [0031](0031-ledger-contracts-module.md) | ledger-contracts module for generated Java and the proto mapping (Proposed) |
| [0032](0032-contract-freeze-and-breaking-baseline.md) | Contract freeze, breaking-change baseline and client tolerance (Proposed) |

A partial change is recorded as `Amends NNNN` in the new ADR and `Amended by NNNN` at the top of the old one;
the old body stays as written.

Template: `# ADR NNNN: Title`, `Status · Date`, then `## Context`, `## Decision`, `## Consequences`, and
`## Alternatives rejected` where it matters.
