# Kimia Ledger

An open-source, production-grade **double-entry ledger service**. Java 25, Spring Boot, Oracle. Apache 2.0.

**Status: pre-alpha, Phase 1 (skeleton, contracts, docs). No domain logic yet.** Not for production use.

## What it is
- N-leg journal entries, balanced per currency, five account types with normal balance sides.
- Integer minor-unit money with ISO-4217 currencies. No floating point anywhere.
- Append-only journal; corrections are reversing entries. History is never edited.
- Synchronous, atomic writes with deterministic locking; events via transactional outbox.
- Mandatory idempotency keys, multi-tenant from day one, no personal data stored.
- Contract-first: `.proto` is the source of truth, REST via gRPC-JSON transcoding, OpenAPI generated.
  Consumers generate their own clients from the published contracts; this repo ships no SDKs.

## Architecture
Hexagonal. Dependencies point inward and are enforced by ArchUnit.

```
contracts/                  proto (source of truth) + generated OpenAPI
ledger-core/                pure domain, JDK only
ledger-application/         use cases + ports
ledger-persistence-oracle/  the only module with SQL (JDBC, Flyway)
ledger-api-rest/            thin REST adapter
ledger-api-grpc/            thin gRPC adapter
ledger-server/              Spring Boot wiring
docs/                       adr/, domain/, security/, integration.md
```

Decisions are recorded in [docs/adr](docs/adr/README.md). Invariants in [docs/domain/invariants.md](docs/domain/invariants.md).

## Build and run
Requires JDK 25 and Docker (Testcontainers).

```bash
./gradlew build                     # compile, tests, ArchUnit, format check
./gradlew :ledger-server:bootRun    # then: curl localhost:8080/health
```

## Integrating
See [docs/integration.md](docs/integration.md). Contracts are published per release.

## Security
See [SECURITY.md](SECURITY.md) for reporting, [docs/security/threat-model.md](docs/security/threat-model.md)
and [docs/security/checklist.md](docs/security/checklist.md).

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence
Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
