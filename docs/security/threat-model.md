# Threat model

Method: STRIDE per asset. Status: **P** planned (decided, not built), **I** implemented, **D** by design/decision.
Reviewed when the architecture or the API changes. Owner: security-reviewer agent + maintainers.

Assets: journal (entries, postings), balances, idempotency records, tenant boundary, credentials and secrets,
build and release pipeline.

Trust boundaries: Internet/consumer → API (mTLS + JWT) → service → Oracle (separate DB users) → broker (via outbox relay).

## 1. Money creation or destruction

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Unbalanced entry persisted | I-4 checked in core under lock; DB check constraint on per-entry sums where feasible; property tests | P |
| Tampering | Balance row changed without postings | Balances updated only inside the entry transaction; reconciliation job (I-10); no UPDATE grant outside that path | P |
| Tampering | Floating-point rounding | Integer minor units only (ADR 0006); static analysis forbids float/double in core | D |
| Tampering | Integer overflow wraps | `Math.*Exact`, `NUMBER(38,0)` | P |
| Elevation | Caller posts to accounts it does not own | Tenant from token; I-6 | P |
| Repudiation | Who created an entry is unknown | Audit columns: client_id, request_id, correlation_id, idempotency_key on every write | P |

## 2. Replay and double-spend

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Spoofing/Replay | Retried request creates a second entry | Mandatory idempotency key, unique per tenant (I-15) | P |
| Tampering | Same key reused with different payload to overwrite | Canonical payload hash compared; conflict error, original untouched | P |
| Tampering | Concurrent duplicates both commit | Unique constraint is the arbiter; loser re-reads winner's result | P |
| Tampering | Lost update on balance under concurrency | `SELECT … FOR UPDATE` in sorted order (I-17); concurrency tests | P |
| DoS | Attacker burns idempotency keys / hot-account lock contention | Rate limiting per client; max postings per entry; lock timeout | P |
| Replay | Captured JWT replayed | Short token lifetime, mTLS binding, audience check | P |

## 3. Tampering with history

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Application updates/deletes a posting | No such code path; runtime DB user lacks UPDATE/DELETE (ADR 0007) | P |
| Tampering | DBA or compromised credential edits rows | Oracle Immutable/Blockchain tables for journal; DB audit; separate migration user | P |
| Tampering | Malicious migration rewrites history | Migrations reviewed, run by separate user, never touch existing journal rows; Flyway checksums | P |
| Repudiation | Disputed history | Append-only journal + sequence_no (I-18) is the evidence; reversal references original | D |
| Information disclosure | Error messages leak SQL or internal ids | Typed error codes only; stack traces never returned | P |

## 4. Privilege escalation between tenants

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Spoofing | Tenant id supplied in body/path | Tenant only from token claim (I-19); API has no tenant field | D |
| Elevation | Query missing tenant filter | All repository methods take tenant_id; tenant-isolation test suite; code review item | P |
| Information disclosure | Existence oracle via distinct errors | Cross-tenant account → ACCOUNT_NOT_FOUND (ADR 0010) | D |
| Elevation | Scope not checked (read token performs write) | Per-RPC scope enforcement in the server; test per RPC | P |
| Elevation | Idempotency record of tenant A returned to B | Key uniqueness and lookup scoped by tenant | P |

## 5. Secrets leakage

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Information disclosure | Secret committed to repo | gitleaks in pre-commit and CI; `.gitignore` for env/keys | I |
| Information disclosure | Secret in logs | No request-body logging; structured logging with redaction list; review item | P |
| Information disclosure | Secret in container image or build args | Secrets from env/Vault at runtime only; no secrets in Dockerfile | P |
| Information disclosure | Test fixtures with real credentials or data | Synthetic fixtures only; Testcontainers random passwords | P |
| Information disclosure | PII inside the immutable journal | ADR 0011; integration guide warning; size limits | D |

## 6. Supply chain

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Malicious dependency version | Exact pins, Renovate PRs reviewed, dependency review in CI, SBOM | P (pins: I) |
| Tampering | Compromised Gradle distribution | Wrapper SHA-256 pinned, `validateDistributionUrl` | I |
| Tampering | Compromised GitHub Action | Actions pinned by commit SHA | P |
| Tampering | Tampered container image | Distroless, non-root, cosign signature verified at deploy | P |
| Tampering | Licence contamination (jOOQ, GPL) | Licence check in review; ADR 0002 | D |
| Tampering | Build script executes downloaded code | Settings deny `curl | sh`; CI uses pinned tool versions with checksums | I |

## Out of scope for v1
Physical access, Oracle vulnerabilities themselves, broker security beyond the outbox relay, client-side key management.

## Open questions
- Hot-account throughput ceiling under row locks; measure before optimising.
- Idempotency record retention.
- Whether `metadata` should be allowed at all in v1 (it is the most likely PII leak path).
