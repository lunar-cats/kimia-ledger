# Security review checklist

Used by the `security-reviewer` agent and by humans on every PR. Mark each item **PASS**, **FAIL** or **N/A**
with `file:line` evidence. Any FAIL in sections A–D blocks the task.

## A. Money integrity
- [ ] A1 Every write path validates I-4 (balanced per currency) in core, under the account locks.
- [ ] A2 No `float`, `double` or non-zero-scale `BigDecimal` anywhere in core/application; integer minor units only.
- [ ] A3 Arithmetic on money uses exact operations; overflow fails.
- [ ] A4 Balances exist only as `balance_after` on inserted posting rows; no UPDATE or DELETE on any ledger table, in code, grants or migrations (ADR 0020).
- [ ] A5 Invariant ids (I-n) are cited where enforced; new rules are added to `docs/domain/invariants.md`.

## B. Replay, idempotency, concurrency
- [ ] B1 Every write RPC requires an idempotency key; absence is rejected.
- [ ] B2 Same key + same operation, client and payload returns the recorded outcome; any difference returns `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`; state- and time-dependent rejections are recorded (ADR 0017, 0018).
- [ ] B3 Distinct accounts are locked with `SELECT … FOR UPDATE WAIT n` in the I-17 comparator order before validation.
- [ ] B4 Entry, postings, reversal link and idempotency record are written in one transaction; the response is sent after commit.
- [ ] B5 Concurrency tests exist for duplicate submits and for contended accounts; lock, statement and transaction timeouts are set (ADR 0026).

## C. History integrity
- [ ] C1 No UPDATE or DELETE statement targets any ledger table (I-13), in code or in migrations.
- [ ] C2 Migrations never modify existing journal rows; Flyway checksums unchanged for applied versions.
- [ ] C3 Reversals are built by the server; the `(tenant_id, original_entry_id)` key rejects a second reversal; a reversal cannot be reversed (I-14).
- [ ] C4 Error responses carry only `ErrorCode` + safe message; no stack traces, SQL, or internal ids.

## D. Tenant isolation and authorization
- [ ] D1 Tenant id comes from the token only; no tenant field in requests.
- [ ] D2 Every SQL statement and every repository method filters by `tenant_id`.
- [ ] D3 Required scope is enforced per RPC; a test proves a token lacking the scope is rejected.
- [ ] D4 Cross-tenant references return `ACCOUNT_NOT_FOUND` (no existence leak).
- [ ] D5 Tenant-isolation tests cover each new query.
- [ ] D6 Every foreign key includes `tenant_id` (ADR 0027).
- [ ] D7 Creating an overdraft account requires `accounts:overdraft`; unknown or suspended tenants are rejected (ADR 0023, 0024).

## E. Secrets, logging, PII
- [ ] E1 No secrets, tokens, or real-looking credentials in code, config, fixtures, or docs (gitleaks clean).
- [ ] E2 Secrets are read from environment/Vault; defaults for production are absent, not weak.
- [ ] E3 Logs never contain request bodies, `external_ref`, tokens, an invalid correlation id, or account ids beyond what audit needs.
- [ ] E4 No new PII-shaped fields (name, email, address, phone, national id) in schema or API.

## F. Input handling
- [ ] F1 Request size, string lengths, postings per entry and page size are bounded before business logic.
- [ ] F2 All SQL uses bind parameters; no string concatenation into SQL.
- [ ] F3 Pagination tokens are opaque and validated; page size has a hard maximum.
- [ ] F4 Rate limiting applies to the new endpoint, or the exemption is justified.

## G. Supply chain and build
- [ ] G1 New dependencies: licence is Apache-2.0/MIT/BSD/EPL, version is exact and current, no known CVEs, justified in the PR.
- [ ] G2 GitHub Actions pinned by commit SHA; no `curl | sh`, no unpinned downloads.
- [ ] G3 Changes to `.github/workflows` were explicitly approved.
- [ ] G4 Container changes keep distroless, non-root, no secrets in layers.

## H. Contracts
- [ ] H1 `buf lint` and `buf breaking` (against the latest release tag once `v1.0.0` exists) pass; no breaking change inside `ledger.v1`, including the non-`buf` cases in ADR 0032.
- [ ] H2 New error situations map to an existing or newly added stable `ErrorCode`, with gRPC status, HTTP status and "recorded" flag in ADR 0018.
- [ ] H3 Amount fields are `int64`, never floating point; currency fields are ISO-4217 codes in v1.
