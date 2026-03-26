# Ledger invariants

Each invariant is numbered, stated precisely, and mapped to the error code a violation returns (`ErrorCode`
enum in `ledger.v1`), or says why it has none. Every invariant gets at least one property-based test. Code
that enforces a rule must cite the invariant id in a comment. Ids are never reused or renumbered.

Status: rewritten on 2026-11-15 for the owner's final decisions, recorded in ADRs 0014–0026, which are
**Proposed**. Until the owner accepts them, the earlier text (git history) is the accepted one. Codes marked
*(new code)* are not yet in `error.proto` (proto follow-up, see review §13). "DB" names the database backing
where one exists (review §7.3); everything else is enforced in `ledger-core`.

Notation: `T` journal transaction, `P` posting, `pos(P)` its position, `asset(P)` its asset,
`amt(P) > 0` amount in minor units of the asset, `dir(P) ∈ {DEBIT, CREDIT}`, `s(P) = +amt(P)` for DEBIT and
`−amt(P)` for CREDIT. A **position** is a bucket, or a direct `(system account, asset)`; a **customer balance
identity** is `(tenant_id, external_account_id, asset_code)` of a customer account.

## Structure

- **I-1 Minimum legs.** Every transaction has at least 2 postings. → `INVALID_ENTRY`
- **I-2 Maximum legs.** Every transaction has at most 100 postings, a constant of `ledger.v1`. DB `CHECK`.
  → `TOO_MANY_POSTINGS`
- **I-3 Positive amounts.** *(changed, ADR 0015)* `1 ≤ amt(P) ≤ assets.max_amount(asset(P))`. Direction is carried
  by `dir(P)`, never by sign. DB `CHECK (amount > 0)`. → `INVALID_AMOUNT`
- **I-4 Balanced per asset.** *(changed, ADR 0017)* For every transaction T and asset a in T:
  `Σ s(P) for P in T, asset(P)=a = 0`, and T has exactly its declared number of postings. Enforced in core and by
  a deferred constraint trigger at commit. → `UNBALANCED_ENTRY`
- **I-5 Asset match.** *(changed, ADR 0021)* `asset(P)` equals the asset of `pos(P)`; a bucket's asset is fixed
  at creation. DB foreign keys. → `ASSET_MISMATCH` *(new code)*
- **I-6 Target exists in tenant.** *(changed, ADR 0021, 0020)* Every posting targets a bucket, a wallet's primary
  bucket, or a system position of the same tenant. Another tenant's target is indistinguishable from a missing
  one. DB composite tenant keys and RLS. → `WALLET_NOT_FOUND`, `BUCKET_NOT_FOUND`, `ACCOUNT_UNKNOWN` *(new codes)*
- **I-7 State allows the posting.** *(changed, ADR 0021)* A posting on a bucket is accepted only if the bucket's
  effective state (most restrictive of wallet and bucket) and the transaction's origin allow it, per the matrix of
  ADR 0021. DB trigger `trg_lp_guard` re-checks. → `WALLET_NOT_ACTIVE`, `WALLET_FROZEN`, `BUCKET_NOT_ACTIVE`
  *(new codes)*
- **I-8 Integer money.** *(changed, ADR 0015)* Amounts are integers in the asset's minor unit; the scale belongs
  to the immutable asset row. Arithmetic is exact (`BigInteger`); overflow is an error. A value outside
  `NUMERIC(38,0)`: I-26.

## Balances

- **I-9 Position balance.** `posted_balance(pos) = Σ s(P)` over committed postings of the position (signed,
  debit-positive). The *presented* balance is `posted_balance` for a debit-normal account and `−posted_balance`
  for a credit-normal account, `normal_side` taken from the account snapshot.
- **I-10 Projection equals journal.** *(changed, ADR 0017, 0025)* For postings `P1..Pn` of a position in
  `position_seq` order: `balance_after(P1) = s(P1)`, `balance_after(Pk) = balance_after(Pk−1) + s(Pk)`, and
  `posted_balance = balance_after(Pn)`, `last_position_seq = n`. Reconciliation verifies it; a mismatch is a
  severity-1 incident, repaired only by the approved runbook with a `projection_repairs` row. No error code.
- **I-11 Effective available.** *(changed, ADR 0022, 0017)* After the position's last posting in a transaction:
  `presented + overdraft_limit − reserved_amount ≥ 0`, where `overdraft_limit` is the per-asset limit of the
  account snapshot used (0 by default; no limit only for system accounts). Reversals get no exemption. Not
  enforced by the database. → `INSUFFICIENT_FUNDS`
- **I-12 Global zero-sum.** For every tenant and asset, Σ `posted_balance` over all positions = 0.
- **I-31 No cross-asset sum.** *(new, ADR 0015, 0025)* No stored or returned total adds different assets, except
  a report that converts with a recorded valuation rate into the tenant's reporting currency.

## Immutability and identity

- **I-13 Append-only.** *(changed, ADR 0014)* Rows of transactions, postings, snapshots, status, hold and
  provisioning events, idempotency records, quotes, valuation rates, settlement items and events, statements,
  discrepancies, resolutions, repairs, policies and assets are never updated or deleted. State tables (cache,
  wallets, buckets, positions, holds, provisioning, batches, settings) are updated but never deleted. Enforced by
  grants and triggers. The delivery tables `outbox_events` and `inbox_events` are the only tables whose old rows are deleted, by the cleanup role only (I-39). `outbox_quarantine` records are append-only and never deleted (I-42). No error code.
- **I-14 Corrections are reversals.** *(changed, ADR 0016)* A reversal is built by the server from the original:
  same positions and amounts, directions flipped. A transaction is reversed at most once (DB partial unique
  index); a reversal cannot be reversed; a reversal is validated like any transaction. → `TRANSACTION_NOT_FOUND`,
  `TRANSACTION_ALREADY_REVERSED`, `TRANSACTION_NOT_REVERSIBLE` *(new codes)*
- **I-15 Idempotency.** `(tenant_id, idempotency_key)` is unique across all write operations, including wallet,
  bucket, hold, FX, settlement and fence operations. Same key, operation, client and canonical payload → the
  recorded outcome; any difference → `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`. Concurrent duplicates produce exactly
  one outcome.
- **I-16 Atomicity.** *(changed, ADR 0024)* A transaction, its postings, position updates, hold changes, outbox
  rows and its idempotency record commit together or not at all. No error code.
- **I-17 Deterministic locking.** *(changed, ADR 0017)* Locks are taken in the global order ① account anchors,
  ② wallets, ③ positions, ④ holds, ⑤ settlement batches, each level sorted (UUIDs in unsigned byte order). Lock
  wait or deadlock → `CONTENTION` *(new code)*. Order prevents deadlock; correctness does not depend on it.
- **I-18 Per-position order.** *(changed, ADR 0017)* The postings of each position carry `position_seq` exactly
  `1..n`, without gaps, in commit order. DB unique `(tenant_id, position_id, position_seq)`. No tenant-wide order.
  A violation is a bug → `INTERNAL`.

## Tenancy and data

- **I-19 Tenant from credential.** `tenant_id` comes from the token, never from the request. Every query filters
  by it; RLS is the second layer.
- **I-20 No PII.** *(changed, ADR 0021)* `external_ref`, `owner_ref` and `external_account_id` are opaque.
  No `metadata`. No error code.
- **I-21 Request limits.** Request size, string lengths, postings per transaction and page size are bounded
  before business logic. → `INVALID_ARGUMENT`

## Wallets, buckets and accounts

- **I-22 Distinct positions.** Every transaction touches at least two distinct positions. → `INVALID_ENTRY`
- **I-23 Immutable identity.** *(changed, ADR 0021)* A bucket's wallet, asset, `external_account_id`,
  `account_scope`, product and primary flag, a wallet's owner and type, and an account's scope never change.
  DB identity triggers. No error code.
- **I-24 Closing.** *(changed, ADR 0021)* A bucket or wallet closes only with `posted_balance = 0`,
  `reserved_amount = 0` and no active hold, under its position locks. `CLOSED` is terminal. →
  `BUCKET_BALANCE_NOT_ZERO`, `WALLET_BALANCE_NOT_ZERO` *(new codes)*
- **I-25 One customer bucket per account and asset.** *(changed, ADR 0021)* For customer accounts,
  `(tenant_id, external_account_id, asset_code)` appears in at most one bucket, for ever; the asset must be
  declared by the account. System accounts are excluded. DB partial unique index and foreign key. →
  `ACCOUNT_ALREADY_BOUND`, `ASSET_NOT_ALLOWED` *(new codes)*
- **I-26 Balance range.** *(changed, ADR 0015)* Every `balance_after` fits `NUMERIC(38,0)`. →
  `BALANCE_LIMIT_EXCEEDED` *(new code)*
- **I-27 Effective time bounds.** `effective_at ≤ posted_at + 5 min` and `≥ posted_at −` the deployment's
  back-dating limit. → `EFFECTIVE_TIME_OUT_OF_RANGE` *(new code)*
- **I-28 Account restrictions.** *(changed, ADR 0022)* A posting or hold uses the account state read under the
  anchor lock: debit needs `can_debit`, credit needs `can_credit`, the asset must be supported, a closed account
  takes nothing. After a fence commits, no posting contrary to it commits. → `ACCOUNT_RESTRICTED`,
  `ASSET_NOT_ALLOWED` *(new codes)*
- **I-29 Registered tenant.** A request is served only if `(issuer, tenant claim)` maps to an `ACTIVE` tenant.
  → `PERMISSION_DENIED`
- **I-30 Overdraft from the Account Service only.** *(changed, ADR 0022)* No request field sets or raises an
  overdraft limit; reductions arrive through the fence. No error code: the field does not exist.
- **I-32 Account version recorded.** *(new, ADR 0022)* Every posting and hold records the `account_version` it
  was validated with; the version exists in `account_snapshots` (DB foreign key). The cache moves only to a
  greater version.
- **I-33 Account seal.** *(new, ADR 0022)* An account close succeeds only if, under its anchor and position
  locks, every position of the account has `posted_balance = 0`, `reserved_amount = 0`, no active hold, and no
  outstanding restriction. → `ACCOUNT_BALANCE_NOT_ZERO`, `ACCOUNT_HAS_RESTRICTIONS` *(new code)*
- **I-34 Provisioning before use.** *(new, ADR 0021)* A wallet takes no posting or hold until every requested
  asset is mapped and the wallet is `ACTIVE`. DB trigger. → `WALLET_NOT_ACTIVE`
- **I-35 Origin is authorized.** *(new, ADR 0021)* `SYSTEM` origin needs scope `transactions:system`;
  `EXCEPTIONAL` needs `transactions:exceptional`, an `approval_ref` and a `reason_code` (DB `CHECK`). Origin is
  derived from the token, never from the request. → `PERMISSION_DENIED`, `APPROVAL_REQUIRED` *(new code)*

## Holds and FX

- **I-36 Hold bounds.** *(new, ADR 0023)* `captured + released ≤ amount` (DB `CHECK`); `reserved_amount` of a
  position equals Σ remaining of its active holds, and `active_hold_count` their number (reconciliation). A hold is
  on one bucket and one asset; its expiry is within its policy's `max_ttl`. → `HOLD_AMOUNT_EXCEEDED`,
  `HOLD_TTL_EXCEEDED` *(new codes)*
- **I-37 Hold transitions.** *(new, ADR 0023)* Release is allowed in every state; capture is refused while
  `FROZEN` (unless `EXCEPTIONAL`) and allowed while `SUSPENDED` only with a policy; no hold is released because a
  provider outcome is unknown. Every transition writes a `hold_events` row. → `WALLET_FROZEN`, `HOLD_NOT_ACTIVE`
- **I-38 FX quote.** *(new, ADR 0025)* An FX transaction cites one unexpired quote, used once (DB unique index),
  and its target amount equals the deterministic rounding of source × execution rate. A reversal uses the
  original amounts. → `FX_QUOTE_EXPIRED`, `FX_QUOTE_USED`, `FX_QUOTE_MISMATCH` *(new codes)*

## Events

- **I-39 Outbox delivery.** *(new, ADR 0024)* An outbox row is deleted only after `published_at` is set and `outbox_retention` has passed, and only by the cleanup path (role `ledger_cleanup`). An unpublished row, including a quarantined one, is never deleted by any role. `published_at` is set once, after the broker acknowledged the record. DB trigger on `outbox_events`; PostgreSQL itself refuses TRUNCATE of `outbox_events` because `outbox_quarantine` references it; the retention age is enforced by the cleanup job. No error code.
- **I-40 Per-aggregate event order.** *(new, ADR 0024)* Events sharing an ordering key (`outbox_events.message_key` = `{tenant_id}:{aggregate_id}`) are published in `outbox_id` order; events with different ordering keys never block each other. The guarantee is per aggregate, not global. A quarantined event (I-42) is the only exception: it is skipped and never sent by the relay. No error code.
- **I-41 Aggregate sequence.** *(new, ADR 0024)* Every Ledger-owned event carries `aggregate_type`, `aggregate_id` and `aggregate_sequence` ≥ 1, unique per aggregate in the outbox (DB unique key). For Wallet, Bucket, Hold and Settlement batch it is the `event_seq` of the history row the event reports; a reconciliation run uses `recon_runs.event_seq`. For a Transaction T it is a domain rule: TransactionPosted(T) = 1; FxExecuted(T) = 2 only if T is FX; TransactionReversed(T) = 2 if T is not FX, 3 if T is FX; each event at most once (ADR 0024 D2.8). It is enforced in `ledger-core` as a pure function of transaction type and event type. Database constraints (unique keys, `ux_ltx_single_reversal`) stop duplicates but do not check the values. No stored counter. Gapless in the outbox; a consumer sees a gap only after a quarantine, a restore or buffer expiry. No error code. The Transaction sequence relies on a single writer per event (ADR 0024 D2.8).
- **I-42 Bypass is recorded.** *(new, ADR 0024)* A blocked event is bypassed only by an append-only `outbox_quarantine` record with `approval_ref`, `operator_id`, `reason_code`, `reason_text` and `blocked_at`, inserted by the break-glass role `ledger_repair` for an unpublished row. The outbox row remains, with `published_at` NULL. No error code (DB trigger, grants).
- **I-43 Operational metadata carries no data.** *(new, ADR 0024)* `outbox_quarantine.reason_code` is from a controlled set. `reason_text` is optional, printable ASCII, 1 to 300 characters, and never contains event payloads, financial data, customer identifiers, secrets or raw exception, stack-trace or SQL text. `outbox_events.last_error` and its copy are error class names only (`[A-Za-z0-9_.:-]{1,200}`). `approval_ref` and `operator_id` are identifiers. The database checks the form (CHECK constraints); the content rule is procedure. Alerts and logs never carry `reason_text`. No error code.
- **I-44 Event envelope is immutable.** *(new, ADR 0024)* The envelope of an outbox row (`event_id`, `event_type`, `schema_version`, `tenant_id`, `aggregate_type`, `aggregate_id`, `aggregate_sequence`, `occurred_at`, `correlation_id`, `producer` (a closed enum), `payload`) is set once, when the row is inserted, and never changes. Re-send, re-publish, replay and quarantine send or keep the same bytes. Only `published_at`, `publish_attempts`, `last_error` and `first_failed_at` change, and only by the relay. Enforced by grants (`ledger_app` inserts only; the relay updates those four columns). No error code.

## Error codes

Defined once, in `contracts/proto/ledger/v1/error.proto` (`ledger.v1.ErrorCode`), with the gRPC and HTTP status
each is delivered with. Values are only ever appended. The mapping is in ADR 0019 (D2) and the additions of ADRs
0021–0025. `ACCOUNT_ALREADY_EXISTS` is deprecated: Ledger no longer creates accounts.

Canonical error names after the 2026-11-15 consistency pass: `ACCOUNT_UNKNOWN` (was `ACCOUNT_NOT_FOUND`), `ACCOUNT_RESTRICTED` (account closed, frozen or lacking a capability), `WALLET_FROZEN` (wallet or bucket freeze), `TRANSACTION_NOT_FOUND`, `TRANSACTION_ALREADY_REVERSED` and `TRANSACTION_NOT_REVERSIBLE` (were `ENTRY_*`), and `ASSET_MISMATCH` (was `CURRENCY_MISMATCH`); `ACCOUNT_CLOSED` and `ACCOUNT_FROZEN` are retired.
