# Security review checklist

Used by the `security-reviewer` agent and by humans on every PR. Mark each item **PASS**, **FAIL** or **N/A**
with `file:line` evidence. Any FAIL in sections A–D blocks the task. Aligned on 2026-11-15 with ADRs 0014–0026
(Proposed).

## A. Money integrity
- [ ] A1 Every write path validates I-4 (balanced per asset) in core, under the position locks; the deferred I-4
  trigger is present in migrations.
- [ ] A2 No `float` or `double` anywhere; money amounts are `BigInteger` / `NUMERIC(38,0)`; `BigDecimal` only for FX
  rates inside `core.fx`.
- [ ] A3 Arithmetic on money is exact; overflow fails.
- [ ] A4 Balances live only in `balance_projections`, changed only in the write path together with a posting
  (or by the repair runbook with a `projection_repairs` row); no `DELETE` or `TRUNCATE` on any ledger table except the cleanup job's DELETE of old published outbox rows and old inbox rows (ADR 0024 D7), and no
  `UPDATE` on append-only tables, in code, grants or migrations (ADR 0014, 0017).
- [ ] A5 Invariant ids (I-n) are cited where enforced; new rules are added to `docs/domain/invariants.md`.
- [ ] A6 Effective available uses the snapshot's normal side and per-asset overdraft limit; no request field
  carries or raises an overdraft limit (I-11, I-30).
- [ ] A7 Transaction `origin` is derived from the token's scopes, never read from the request; `EXCEPTIONAL`
  requires `approval_ref` and `reason_code` (I-35).

## B. Replay, idempotency, concurrency
- [ ] B1 Every write RPC (transactions, holds, wallets, buckets, FX, settlement, fence) requires an idempotency
  key.
- [ ] B2 Same key + operation + client + payload returns the recorded outcome; any difference returns
  `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`; state- and time-dependent rejections are recorded (ADR 0018, 0019).
- [ ] B3 Locks follow the global order ① anchors `FOR KEY SHARE`, ② wallets, ③ positions `FOR UPDATE`, ④ holds,
  ⑤ batches, each sorted (I-17); `lock_timeout`, `statement_timeout` and `transaction_timeout` are set.
- [ ] B4 State change, postings, position updates, hold changes, outbox rows and idempotency record are in one
  transaction; the response is sent after commit.
- [ ] B5 No network call (Account Service, Kafka) happens while the database transaction is open.
- [ ] B6 Concurrency tests exist for duplicate submits, contended positions, capture versus expiry, and fence
  versus posting.
- [ ] B7 The relay sets `published_at` only after the broker ack and never deletes. Deletion happens only in the
  cleanup job under `ledger_cleanup`, only for published outbox rows older than `outbox_retention` and inbox rows
  older than `inbox_retention`. Retention chain: Kafka <= outbox <= inbox, 7 <= 14 <= 30 days (proposals; ADR 0024 D1, D7).
- [ ] B8 Events sharing a `message_key` (`{tenant_id}:{aggregate_id}`) are published in `outbox_id` order; the guarantee is per aggregate, never global. Only the head of a key is sent, and a failing event blocks only its key. A quarantined event (I-42) is the only exception. Tests include a permanently failing key while other keys keep flowing (I-40, ADR 0024 D3.2, D7.3a).
- [ ] B9 A blocked event is bypassed only by an `outbox_quarantine` record (`approval_ref`, `operator_id`, `reason_code`, `reason_text`, `blocked_at`). No role deletes an unpublished outbox row. `reason_text` is optional ASCII text of at most 300 characters with no payload, financial data, customer identifiers or secrets. Every quarantine insert raises an alert that carries `outbox_id`, `reason_code` and `approval_ref`, never `reason_text` (I-42, I-43, ADR 0024 D7.12).
- [ ] B10 Every Ledger-owned event carries `aggregate_type`, `aggregate_id` and `aggregate_sequence`; history rows are emitted 1:1 in the same transaction (I-41, ADR 0024 D2).
- [ ] B11 The event envelope carries the minimum fields of ADR 0024 D2 and is never rewritten: only the relay updates the four delivery columns, and a re-publish sends the stored bytes (I-44).
- [ ] B12 Transaction events are written only by the Ledger write path, in the creating transaction; `TransactionReversed` only by `ReverseTransaction` under the position locks; `ux_ltx_single_reversal` is kept (I-41, ADR 0024 D2.8). The sequence values are computed in `ledger-core` and covered by a property test; the database does not check them.

## C. History integrity
- [ ] C1 No `UPDATE`, `DELETE` or `TRUNCATE` targets an append-only table (I-13), in code or migrations; state
  tables are never deleted.
- [ ] C2 Migrations never modify existing journal rows; Flyway checksums unchanged for applied versions.
- [ ] C3 Reversals are built by the server; a second reversal is refused; a reversal cannot be reversed (I-14).
- [ ] C4 Error responses carry only `ErrorCode` + safe message.

## D. Tenant isolation and authorization
- [ ] D1 Tenant id comes from the token only.
- [ ] D2 Every SQL statement and repository method filters by `tenant_id`; the transaction sets `app.tenant_id`
  with `set_config(…, true)`; RLS policies exist for every new tenant table.
- [ ] D3 Required scope is enforced per RPC (`wallets:*`, `transactions:*`, `holds:*`, `fx:write`,
  `settlement:write`, `accounts:fence`); a test proves a token lacking it is rejected.
- [ ] D4 Cross-tenant targets return the same code as missing ones.
- [ ] D5 Tenant-isolation tests cover each new query.
- [ ] D6 Every foreign key includes `tenant_id`; no `ON DELETE CASCADE` (ADR 0020, 0014).
- [ ] D7 `ApplyAccountState` accepts only the Account Service identity. No client field creates or binds an
  account: bucket-account associations come only from Account Service output (provisioning or lookup). A client may
  name a system account's `external_account_id` as a posting target only if it resolves to a SYSTEM-scope account,
  else `ACCOUNT_UNKNOWN` (ADR 0021 D1.4). A customer account id is never a posting target.

## E. Secrets, logging, PII
- [ ] E1 No secrets or real-looking credentials in code, config, fixtures or docs (gitleaks clean).
- [ ] E2 Secrets come from environment/Vault; production defaults are absent, not weak.
- [ ] E3 Logs never contain request bodies, `external_ref`, `owner_ref`, tokens or account ids beyond what audit
  needs.
- [ ] E4 No new PII-shaped fields in schema or API.
- [ ] E5 Operational metadata (`last_error`, quarantine `reason_code`, `reason_text`, `approval_ref`, `operator_id`) holds only controlled codes, identifiers or short operator text; never payloads, financial data, customer identifiers, secrets or raw exceptions; alerts and logs never carry `reason_text` (I-43, ADR 0024 D7.12).

## F. Input handling
- [ ] F1 Request size, string lengths, postings per transaction and page size are bounded before business logic.
- [ ] F2 SQL uses bind parameters (jOOQ or explicit SQL); no string concatenation into SQL.
- [ ] F3 Pagination tokens are opaque and validated; page size has a hard maximum.
- [ ] F4 Rate limiting applies to the new endpoint, or the exemption is justified.

## G. Supply chain and build
- [ ] G1 New dependencies: Apache-2.0/MIT/BSD/EPL licence, exact current version, no known CVEs, justified. jOOQ
  only as OSS and only in `ledger-persistence-postgres`.
- [ ] G2 GitHub Actions pinned by commit SHA; no `curl | sh`.
- [ ] G3 Changes to `.github/workflows` were explicitly approved.
- [ ] G4 Container changes keep distroless, non-root, no secrets in layers.

## H. Contracts
- [ ] H1 `buf lint` and `buf breaking` pass; no breaking change inside `ledger.v1` after `v1.0.0` (ADR 0019).
- [ ] H2 New error situations map to a stable `ErrorCode` with gRPC status, HTTP status and "recorded" flag.
- [ ] H3 Amount fields are decimal strings of integers in minor units, never floating point; asset fields are
  codes from the `assets` table (ADR 0015).
