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
- ADRs 0014–0032 (Proposed) from the 2026-11-14 architecture review and an independent audit; invariants, threat model and security checklist aligned with them.
