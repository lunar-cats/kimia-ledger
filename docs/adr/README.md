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

Template: `# ADR NNNN: Title`, `Status · Date`, then `## Context`, `## Decision`, `## Consequences`.
