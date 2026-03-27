# Kimia Ledger

An open-source, production-grade **double-entry ledger service**. Java 25, Spring Boot 4.x, PostgreSQL 18. Apache 2.0.
Deployed as an independent service; accounts are owned by an external Account Service, wallets and balances by the ledger.

**Status: pre-alpha, Phase 1 (skeleton, contracts, docs). No domain logic yet.** Not for production use.

## What it is
- N-leg journal transactions, balanced per asset (fiat, crypto, token, stock).
- Wallets with balance buckets, one bucket per customer account and asset; native holds with partial capture.
- Integer minor-unit money as `NUMERIC(38,0)` / `BigInteger`, decimal strings on the wire. No floating point.
- Append-only journal with a transactional balance projection; corrections are reversals. History is never edited.
- Synchronous, atomic writes with deterministic locking; events to Kafka via transactional outbox and inbox.
- Mandatory idempotency keys, multi-tenant from day one, no personal data stored.
- Contract-first: `.proto` is the source of truth. REST is served by a hand-written adapter that follows the `google.api.http` mapping in the protos. OpenAPI is generated.
  Consumers generate their own clients from the published contracts; this repo ships no SDKs.

## Architecture
Hexagonal. Dependencies point inward and are enforced by ArchUnit.

```
contracts/                  proto (source of truth) + generated OpenAPI
ledger-core/                pure domain, JDK only
ledger-application/         use cases + ports
ledger-persistence-oracle/  PENDING RENAME to ledger-persistence-postgres; the only module with SQL (jOOQ, JDBC, Flyway)
ledger-api-rest/            thin REST adapter
ledger-api-grpc/            thin gRPC adapter
ledger-contracts/           generated protobuf/gRPC stubs only
ledger-messaging-kafka/     outbox relay, inbox consumers
ledger-account-client/      gRPC client to the Account Service
ledger-server/              Spring Boot wiring
docs/                       adr/, domain/, security/, integration.md
```

Decisions are recorded in [docs/adr](docs/adr/README.md); ADRs 0014–0026 (Proposed) describe the target design. Invariants in [docs/domain/invariants.md](docs/domain/invariants.md).

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
