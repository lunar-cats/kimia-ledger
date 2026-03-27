# Changelog

All notable changes to this project are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/).

## [Unreleased]

### Added
- Gradle multi-module skeleton (Java 25, Spring Boot 4.1.2), hexagonal modules, ArchUnit dependency rules.
- `/health` endpoint (actuator).
- `ledger.v1` API contract draft: CreateAccount, GetAccount, PostEntry, GetEntry, GetBalance, ListPostings with
  gRPC-JSON transcoding, stable `ErrorCode` enum, idempotency key, correlation id, pagination; generated OpenAPI 3.0;
  `buf lint` (STANDARD + COMMENTS), `buf format`, `buf breaking` configuration.
- Agent team setup, permission deny list, pre-commit hook (gitleaks, format, fast tests).
- ADRs 0001–0013, ledger invariants, STRIDE threat model, security checklist, integration guide.
- SECURITY.md, CONTRIBUTING.md, README.md, NOTICE.
- ADRs 0014–0026 (Proposed), from the 2026-11-14 architecture review, an independent audit and the owner's final decisions of 2026-11-15, consolidated from 30 drafts into 13 (PostgreSQL 18 with jOOQ, `NUMERIC(38,0)` money, Ledger-owned wallets with an external Account Service, wallet states, provisioning saga, fence and seal, native holds, Kafka outbox/inbox, FX valuation, independent Java service); invariants, security docs, architecture review with a re-run DDL, `CLAUDE.md`, `README.md` and `CONTRIBUTING.md` aligned with them.
