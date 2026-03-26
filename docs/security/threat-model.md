# Threat model

Method: STRIDE per asset. Status: **P** planned (decided, not built), **I** implemented, **D** by design/decision.
Reviewed when the architecture or the API changes. Owner: security-reviewer agent + maintainers.

Assets: journal (transactions, postings with position chains), balance positions and holds, wallets and buckets,
account cache and snapshots, idempotency records, tenant boundary and registry, credentials and secrets, build
and release pipeline.

Trust boundaries: consumer → API (mTLS + JWT) → service → PostgreSQL 18 (separate roles, RLS); service ↔ Kafka
(outbox relay, inbox consumers, and the cleanup job under `ledger_cleanup`); service → Account Service (`BatchGetAccounts`, `ProvisionAccounts`); Account
Service → service (`ApplyAccountState`, `accounts:fence`).

Rewritten on 2026-11-15 for ADRs 0014–0026 (Proposed); status stays **P** until built.

## 1. Money creation or destruction

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Unbalanced transaction persisted | I-4 in core and a deferred constraint trigger at commit (ADR 0017); reconciliation re-checks | P |
| Tampering | Position changed without a posting | `ledger_app` has `UPDATE` on positions (needed for row locks); reconciliation checks position = last `balance_after` and the gapless chain (I-10); repair only by runbook with `projection_repairs` and `ledger_repair` (ADR 0025) | P |
| Tampering | Floating-point rounding | `NUMERIC(38,0)` / `BigInteger`; rates only in `core.fx` (ADR 0015) | D |
| Tampering | Overflow | `NUMERIC(38,0)` bounds, `assets.max_amount`, `BALANCE_LIMIT_EXCEEDED` | P |
| Elevation | Client grants itself overdraft | No request field sets a limit; limits come only from Account Service snapshots; reductions fenced (ADR 0022, I-30) | D |
| Elevation | Stale cache grants a withdrawn overdraft or capability | Restrictive changes use the synchronous fence before the Account Service reports success; stale or missing entries refreshed before `BEGIN`; fail closed (ADR 0022) | P |
| Elevation | Caller marks its own operation as system or exceptional | Origin derived from scopes `transactions:system` / `transactions:exceptional`; `EXCEPTIONAL` needs `approval_ref` from an external approval workflow; DB `CHECK` and `trg_lp_guard` (ADR 0021) | P |
| Elevation | Two wallets claim the same customer account and asset | Partial unique index on customer buckets (I-25) | P |
| Elevation | Client with `transactions:write` posts from any bucket of its tenant | Accepted residual risk; per-wallet posting rights are a later decision | D |
| Repudiation | Who did an operation is unknown | `client_id`, `request_id`, `correlation_id`, `idempotency_key`, `origin`, `approval_ref`, `reason_code` on every write | P |

## 2. Replay and double-spend

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Spoofing/Replay | Retried request creates a second effect | Mandatory idempotency key, unique per tenant (I-15) | P |
| Tampering | Same key, different payload | Canonical hash compared; conflict | P |
| Tampering | Concurrent duplicates both commit | Advisory lock on the key at transaction start; primary key backstop | P |
| Tampering | Lost update under concurrency | Sorted `FOR UPDATE` on positions (I-17); unique `(tenant, position, position_seq)` (I-18) | P |
| Tampering | Hold captured twice or beyond its amount | `CHECK captured + released ≤ amount`; hold lock ④ (I-36) | P |
| Tampering | FX quote reused | Unique index on `fx_quote_id` (I-38) | P |
| Tampering | Duplicate or out-of-order account event regresses state | Inbox de-duplication; cache moves only to a greater `account_version` (I-32) | P |
| DoS | Key burning, hot-account contention | Rate limits, per-tenant cap, lock/statement/transaction timeouts (ADR 0017) | P |
| DoS | Account Service outage | Fails closed only for operations whose cache entry is stale or missing (ADR 0022) | D |
| Replay | Captured JWT replayed | Short lifetime, mTLS, audience check | P |
| Replay | Duplicate delivery after a crash between broker ack and mark | Stable `event_id`, inbox de-duplication, inbox retention > outbox retention (ADR 0024 D7) | P |
| Spoofing | Forged `ApplyAccountState` | gRPC internal only, mTLS identity of the Account Service, scope `accounts:fence` | P |
| Spoofing | Forged `AccountStateChanged` on Kafka | Topic ACL: only the Account Service produces `accounts.lifecycle.v1`; version check | P |

## 3. Tampering with history

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Application updates or deletes journal rows | No grant; triggers refuse `UPDATE`/`DELETE`/`TRUNCATE` (ADR 0014) | P |
| Tampering | DBA, owner or superuser edits rows or disables triggers | pgaudit on DDL and role changes (licence unverified), break-glass superuser, reconciliation chain checks. Weaker than Oracle immutable tables; accepted (ADR 0014) | P |
| Tampering | Malicious migration rewrites history | Reviewed migrations, separate migrator role, Flyway checksums, no `ALTER … TYPE` on append-only tables | P |
| Repudiation | Disputed history | Append-only journal, gapless `position_seq`, `account_version` per posting, `hold_events`, status events | D |
| Information disclosure | Errors leak SQL or ids | Typed error codes only | P |
| Tampering | Events lost before delivery (unpublished outbox rows deleted) | No DELETE grant for `ledger_relay` or `ledger_app`; `ledger_cleanup` deletes only published rows; trigger refuses DELETE of unpublished rows; alert on oldest pending age (ADR 0024 D7) | P |
| Tampering | An operator silently drops an undeliverable event | No role deletes unpublished rows; bypass only via an append-only quarantine record with `approval_ref` and `operator_id`; the row stays; alert on every insert (ADR 0024 D7.12) | P |
| Elevation | Quarantine used to hide events or reorder a key | Only the break-glass role `ledger_repair`, granted per incident; only unpublished rows; immutable record; consumers see the sequence gap (ADR 0024 D7.12) | P |
| Tampering | `published_at` reset to hide or force re-delivery | Set-once trigger on `outbox_events`; replay never touches the flag (ADR 0024 D7) | P |

## 4. Privilege escalation between tenants

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Spoofing | Tenant id in body or path | Tenant only from the token (I-19) | D |
| Elevation | Query missing tenant filter | Explicit predicate, composite tenant foreign keys, RLS with `FORCE` as second layer, fail closed when unset (ADR 0014) | P |
| Spoofing | Unregistered or suspended tenant | Registry keyed by issuer; `PERMISSION_DENIED` (I-29) | P |
| Elevation | Idempotency outcome read across clients | Record stores `client_id` | P |
| Information disclosure | Existence oracle | Cross-tenant targets return `WALLET_NOT_FOUND` / `BUCKET_NOT_FOUND` / `ACCOUNT_UNKNOWN` like missing ones | D |
| Elevation | Scope not checked | Per-RPC scope enforcement and tests | P |
| Elevation | Cross-tenant worker leaks | Workers set `app.tenant_id` per tenant, one tenant per transaction | P |

## 5. Secrets and personal data

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Information disclosure | Secret in repo | gitleaks in pre-commit and CI | I |
| Information disclosure | Secret in logs | No body logging; redaction list | P |
| Information disclosure | Secret in image | Runtime secrets from env/Vault only | P |
| Information disclosure | Real data in fixtures | Synthetic fixtures; random container passwords | P |
| Information disclosure | PII in the immutable journal | Opaque `external_ref`, `owner_ref`, `external_account_id` (no IBAN or account number); no `metadata` (I-20) | D |
| Information disclosure | Provider payloads in Ledger | Not stored; timeline is in Payments (ADR 0021) | D |
| Information disclosure | Payload or PII leaks into a quarantine reason or an error text | Controlled `reason_code`; optional printable ASCII `reason_text` (1 to 300 characters); `last_error` is an error class name only (DB CHECK); identifier-shaped `approval_ref` and `operator_id`; content rule by procedure and grant review; alerts carry the code, not the text; read access limited to operations and compliance (I-43, ADR 0024 D7.12) | P |
| Information disclosure | Raw exception message in `last_error` leaks payload or hostnames | CHECK allows only an error class name; the relay maps broker errors to class names (ADR 0024 D7.12) | P |

## 6. Supply chain

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| Tampering | Malicious dependency version | Exact pins, Renovate, dependency review, SBOM | P (pins: I) |
| Tampering | Compromised Gradle distribution | Wrapper SHA-256 pinned | I |
| Tampering | Compromised GitHub Action | Pinned by commit SHA | P |
| Tampering | Tampered image | Distroless, non-root, cosign | P |
| Tampering | Licence contamination | Licence check; jOOQ OSS only while PostgreSQL stays in its "latest versions" window, CI check (ADR 0014) | P |
| Tampering | Build script runs downloaded code | Deny `curl | sh`; pinned tools | I |

## 7. Availability and recovery

| STRIDE | Threat | Mitigation | Status |
|---|---|---|---|
| DoS | Acknowledged commit lost on failover | Synchronous replication; failover only to a synchronous standby; RPO 0 inside the HA domain (ADR 0014) | P |
| DoS | Events published for a transaction lost in a cross-region restore | Consumers reconcile events against the API; cross-region RPO not decided | P |
| DoS | Relay down, events delayed | Outbox depth and age alerts; single active relay (leader by advisory lock); failing key blocks only itself, other keys keep flowing (ADR 0024 D1, D3.2) | P |
| DoS | One entity's permanently failing event blocks its key's stream | Per-key blocking: other keys unaffected; `blocked_keys` and oldest-blocked-head alerts; no skip, reorder or automatic delete; a quarantine record with approval is the only bypass (I-42, ADR 0024 D3.2, D7.12) | P |
| DoS | Cleanup stuck, outbox and inbox grow | Alert when the oldest published row is older than 2x `outbox_retention`; retention chain Kafka <= outbox <= inbox, 7 <= 14 <= 30 days (proposals) (ADR 0024 D1, D7) | P |

## Out of scope for v1
Physical access, PostgreSQL and Kafka vulnerabilities themselves, client-side key management, crypto short
positions and securities margin (ADR 0022).

## Open questions
- Cross-region DR RPO/RTO (ADR 0014).
- Legal retention of journal, idempotency records and payloads (ADR 0014, 0021).
- Exact Account Service freshness SLO (ADR 0022).

Decided, not open: certificate-bound tokens are required by default (ADR 0020 D1.4); per-wallet posting
rights are an accepted residual risk (section 1).
