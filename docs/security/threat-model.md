# Threat model

Method: STRIDE per asset. Status: **P** planned (decided, not built), **I** implemented, **D** by design/decision.
Reviewed when the architecture or the API changes. Owner: security-reviewer agent + maintainers.

Assets: journal (entries, postings with running balances), idempotency records, tenant boundary and tenant
registry, credentials and secrets, build and release pipeline.

Trust boundaries: Internet/consumer → API (mTLS terminated in the service + JWT) → service → Oracle (separate DB
users). No broker in v1 (ADR 0021).

Rows changed on 2026-11-14 follow ADRs 0014–0032, which are Proposed; status stays **P** until built.

## 1. Money creation or destruction

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Unbalanced entry persisted | I-4 checked in core before insert; property tests; reconciliation re-checks I-4 per entry (ADR 0029). The database does not enforce I-4 (ADR 0020) | P |
| Tampering | Balance changed without postings | No balance table; running balance is an insert-only column of each posting; runtime user has no UPDATE/DELETE on any table (ADR 0020); reconciliation checks the chain and freezes on mismatch (I-10, I-28) | P |
| Tampering | Floating-point rounding | Integer minor units only (ADR 0006); static analysis forbids float/double in core | D |
| Tampering | Integer overflow wraps | `Math.*Exact`; `NUMBER(19,0)` with `int64` range `CHECK`; `BALANCE_LIMIT_EXCEEDED` (ADR 0019) | P |
| Elevation | Caller posts to accounts it does not own | Tenant from token; I-6; composite tenant foreign keys (ADR 0027) | P |
| Elevation | Client creates an overdraft account and mints money | Scope `accounts:overdraft` required (ADR 0024, I-30) | P |
| Elevation | Client with `entries:write` posts to or from any account of its tenant | Accepted risk in v1: no per-account rights (ADR 0024) | D |
| Repudiation | Who created an entry is unknown | Audit columns: client_id, request_id, correlation_id, idempotency_key on every write | P |

## 2. Replay and double-spend

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Spoofing/Replay | Retried request creates a second entry | Mandatory idempotency key, unique per tenant (I-15) | P |
| Tampering | Same key reused with different payload to overwrite | Canonical payload hash compared; conflict error, original untouched | P |
| Tampering | Concurrent duplicates both commit | Unique constraint is the arbiter; loser re-reads winner's result | P |
| Tampering | Lost update on balance under concurrency | `SELECT … FOR UPDATE` in sorted order (I-17); unique `(tenant, account, account_seq)` (I-18); concurrency tests | P |
| Tampering | Rejected request succeeds on later redelivery | State- and time-dependent rejections recorded under the key (ADR 0017) | P |
| DoS | Attacker burns idempotency keys / hot-account lock contention | Rate limiting per client in the service; per-tenant write cap; lock, statement and transaction timeouts (ADR 0023, 0026); max postings per entry | P |
| DoS | Compromised runtime DB credential locks tables (`SELECT` allows `FOR UPDATE`/`LOCK TABLE` on Oracle, unverified) | Credential from Vault; DB audit; timeouts | P |
| Replay | Captured JWT replayed | Short token lifetime, mTLS binding, audience check | P |

## 3. Tampering with history

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Application updates/deletes a posting | No such code path; runtime DB user lacks UPDATE/DELETE (ADR 0007) | P |
| Tampering | DBA or compromised credential edits rows | Oracle Immutable/Blockchain tables for journal; DB audit; separate migration user | P |
| Tampering | Malicious migration rewrites history | Migrations reviewed, run by separate user, never touch existing journal rows; Flyway checksums | P |
| Repudiation | Disputed history | Append-only journal + gapless per-account `account_seq` (I-18) is the evidence; reversal link row references the original | D |
| Information disclosure | Error messages leak SQL or internal ids | Typed error codes only; stack traces never returned | P |

## 4. Privilege escalation between tenants

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Spoofing | Tenant id supplied in body/path | Tenant only from token claim (I-19); API has no tenant field | D |
| Elevation | Query missing tenant filter | All repository methods take tenant_id; composite tenant foreign keys stop cross-tenant writes (ADR 0027); tenant-isolation test suite; code review item. Row policies (VPD) deferred | P |
| Spoofing | Token for a tenant that was never set up or is suspended | Issuer and audience allow-list; tenant registry keyed by issuer; unknown or suspended → `PERMISSION_DENIED` (ADR 0023, I-29) | P |
| Elevation | Idempotency outcome of client A read by client B of the same tenant | Record stores `client_id`; other client → conflict (ADR 0017) | P |
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
| Information disclosure | PII inside the immutable journal | ADR 0011; no `metadata` field (ADR 0025); integration guide warning; size limits | D |

## 6. Supply chain

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Malicious dependency version | Exact pins, Renovate PRs reviewed, dependency review in CI, SBOM | P (pins: I) |
| Tampering | Compromised Gradle distribution | Wrapper SHA-256 pinned, `validateDistributionUrl` | I |
| Tampering | Compromised GitHub Action | Actions pinned by commit SHA | P |
| Tampering | Tampered container image | Distroless, non-root, cosign signature verified at deploy | P |
| Tampering | Licence contamination (jOOQ, GPL) | Licence check in review; ADR 0002 | D |
| Tampering | Oracle JDBC driver redistributed against its terms | Not bundled in published images until a human signs off the exception (ADR 0028) | P |
| Tampering | Build script executes downloaded code | Settings deny `curl | sh`; CI uses pinned tool versions with checksums | I |

## Out of scope for v1
Physical access, Oracle vulnerabilities themselves, brokers and events (none in v1, ADR 0021), client-side key management.

## Open questions
- Hot-account throughput ceiling under row locks; measure before optimising (ADR 0026).
- Idempotency record retention (no deletion in v1, ADR 0029).
- Default for certificate-bound tokens (ADR 0023).
- Per-account posting rights (ADR 0024).
- Resolved: `metadata` is not in v1 (ADR 0025).
