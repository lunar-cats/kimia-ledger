# ADR 0017: Balance projection, write path, per-position order, lock order, timeouts and concurrency limits

Status: Proposed · Date: 2026-11-15 · Amends 0005, 0007, 0008, 0013

Replaces earlier drafts: old 0038, old 0014, old 0026; old 0020 (superseded, see Alternatives).

## Context
Review §5.1, §7.3 (`balance_projections`, `ledger_transactions`, `ledger_postings`, triggers `trg_lp_balanced`,
`trg_lp_guard`), §8.1–§8.3, §11, §14.3, §19; review finding B-1; audit findings R-1, R-4.

Owner baseline: mutable transactional balance projection with an immutable journal; light CQRS; no full event
sourcing. Insert-only balances (old 0020) cannot carry reservations, and PostgreSQL row locks need `UPDATE`.

Ordering (old 0014): a number assigned before commit can commit out of order, so a cursor over a tenant-wide
number skips late commits; keeping such a promise needs one lock per tenant until commit, which serialises every
write of a tenant. Nothing needs a tenant-wide order. Limits (old 0026): no document stated a lock-wait,
statement or transaction timeout, a per-tenant cap or a capacity target; ADR 0008 accepted hot-account
contention without saying what it costs.

## Decision

### D1. Positions and effective available (old 0038)
1. `balance_projections` is the only balance store: one row per bucket, or per `(system account, asset)` direct
   position. Columns `posted_balance` (signed, debit-positive, I-9), `reserved_amount`, `last_position_seq`,
   `active_hold_count`, `version`, `created_at`, `updated_at`. A customer balance identity
   `(tenant, external_account_id, asset_code)` is exactly one position (ADR 0021 D2).
2. **Effective available** = presented balance (by the snapshot's `normal_side`) + applicable overdraft limit
   (ADR 0022 D8; 0 by default) − `reserved_amount`. Checked in `ledger-core` after the position's last posting in
   the transaction. The database does not enforce it.

### D2. Immutable posting chain and per-position order (old 0038, old 0014)
1. Every posting stores `position_seq` (`1..n` per position, unique `(tenant, position_id, position_seq)`),
   `balance_after` (signed balance after that posting, I-9), and the exact `account_version` (foreign key to
   `account_snapshots`). Transactions store `origin` (`CLIENT`, `SYSTEM`, `EXCEPTIONAL`), `policy_ref`,
   `reason_code`, `approval_ref` (ADR 0021 D5).
2. `ledger.v1` promises **no tenant-wide order**; `sequence_no` and `as_of_sequence_no` leave the contract.
   For each position the committed postings carry exactly `1..n`: no gaps, no duplicates, in commit order. The
   number is `last_position_seq + 1`, assigned under the position lock and updated in place; a rollback leaves no
   number behind. A unique violation is a server bug → `INTERNAL`.
3. `ListBucketPostings` and `ListAccountPostings` return postings in ascending `position_seq`; the page token
   encodes the last number and is valid only for the same position. Pages are stable: no posting with a lower
   number of the same position can commit later, so a reader never skips or repeats one. A consumer detects a
   missed posting by a gap.
4. **Any tenant-wide feed** (events, a transaction list) must not be a cursor over a number assigned before
   commit: it is claimed by status from an outbox (ADR 0024 D3) or reads behind a safe horizon.

### D3. Database checks
Deferred constraint trigger `trg_lp_balanced` for I-4 (per asset, posting count). `trg_lp_guard` refuses a
posting whose bucket does not match its position, and a posting the wallet/bucket state matrix of ADR 0021 D4
forbids. Row `CHECK`s cover amount, direction and `position_seq ≥ 1`. Not enforced in the database: overdraft,
reversal of a reversal, the `balance_after` chain (core and reconciliation, ADR 0025 D3).

### D4. Global lock order (old 0038)
Each path takes a subset, never a lower level before a higher one:

| Level | Row | Lock | Order |
|---|---|---|---|
| ① | `account_state_cache` | posting, hold, bucket creation: `FOR KEY SHARE`; account state: `FOR UPDATE` | `external_account_id` `COLLATE "C"` |
| ② | `wallets` | wallet and bucket lifecycle only: `FOR UPDATE` | `wallet_id` |
| ③ | `balance_projections` | `FOR UPDATE` | `position_id`, unsigned byte order |
| ④ | `holds` | `FOR UPDATE` | `hold_id` |
| ⑤ | `settlement_batches` | `FOR UPDATE` | `batch_id` |

UUIDs compare in unsigned byte order of the 16 bytes, which equals the order of the canonical lowercase string;
`java.util.UUID.compareTo` is not used (it has compared signed halves; unverified for Java 25). A test pins the
comparator. Lock order is a liveness property: a violation causes a deadlock, mapped to `CONTENTION`; it cannot
corrupt a balance. One lock statement per position in v1.

### D5. PostTransaction write path (old 0038; review §8.2)
Targets: `bucket_id`, wallet plus asset (primary bucket), or system `external_account_id` plus asset.
- Before the transaction: (1) mTLS, JWT, tenant, scope, origin from the token (ADR 0021 D5), rate limit,
  per-tenant cap; (2) shape and payload-only rules, asset `max_amount`; (3) `request_hash` (ADR 0018);
  (4) resolve targets without locks; (5) account freshness and `BatchGetAccounts` (ADR 0022 D3), fail closed.
- In one `READ COMMITTED` transaction: (6) `SET LOCAL lock_timeout, statement_timeout, transaction_timeout`;
  `set_config('app.tenant_id', …, true)`. (7) `pg_advisory_xact_lock(key64)`; idempotency lookup → replay or
  conflict. (8) ① anchors `FOR KEY SHARE`, sorted; missing → `ACCOUNT_UNKNOWN`. (9) Create a missing system direct
  position (`ON CONFLICT DO NOTHING`). (10) ③ positions `FOR UPDATE`, sorted. (11) Read wallet, bucket and
  account-asset state. (12) `ledger-core`: state matrix, capabilities, asset support, effective available, I-5,
  I-27; a recorded rejection goes to step 16 only. (13) Insert the transaction and postings with `position_seq`,
  chained `balance_after`, `account_version`. (14) Update positions. (15) Outbox rows (ADR 0024 D1).
  (16) Idempotency record, last statement. (17) Commit; I-4 fires.
- Respond only after commit; a lost response is retried with the same key. `ReverseTransaction` runs steps 6–17
  on the original's positions (ADR 0016 D3).
- **Errors.** `55P03`, `57014`, `40P01` → `CONTENTION`. Unique violation on `position_seq`, I-4 trigger or guard
  trigger → `INTERNAL` (a bug: core should have refused first). PostgreSQL aborts the whole transaction on any
  error, so nothing continues after one.

### D6. Reads (light CQRS)
Position reads by primary key, no lock; `as_of_position_seq` returned (0 for an empty position) with `as_of` (the
`posted_at` of that posting; `created_at` for an empty position). Balance and number come from the same row.
Read replicas only if measured.

### D7. Timeouts, per-tenant cap and hot positions (old 0026)
1. **Every write has a deadline**: the smaller of the server maximum and the caller's gRPC deadline minus a
   margin. REST requests use the server maximum.
2. **Three bounds**, set per transaction with `SET LOCAL` (D5 step 6), all mapped to `CONTENTION` when hit:
   `lock_timeout` (lock wait), `statement_timeout` (also bounds the advisory-lock wait), `transaction_timeout`
   (PostgreSQL 17+, unverified). `idle_in_transaction_session_timeout` is set too. Values: to be measured.
3. **Per-tenant cap** on in-flight write transactions per instance, and so on the connections one tenant can
   hold. Over the cap → `RATE_LIMITED` without touching the database. Value to be measured.
4. **Hot positions are serialised** in v1. Mitigation: split a hot system account (for example a fee account)
   into N positions, choose one per transaction, sum them for reporting.
5. Clients retry `CONTENTION` with the same key, exponential backoff and jitter. Numbers come from configuration
   with no default in production profiles; the capacity method below sets them.

### D8. Projection repair
Never automatic; a human-approved runbook (ADR 0025 D3).

## Capacity method and benchmarks
**Owner decision (2026-11-15):** no invented TPS or latency numbers. Capacity is derived from Sarv production
traffic, postings per transaction, hot-account (fee, clearing) contention, multi-asset and hold workloads, and
reporting queries; Sarv's `distributed`, `hot` and `fee` scenarios are the starting point; Kimia is benchmarked
independently. Nothing is marked verified before benchmarks.

1. Inputs from Sarv production: transactions and postings per hour at peak, postings per transaction, share
   touching the fee account, hold create/capture ratio, report query mix.
2. Kimia measures, on the target PostgreSQL 18 HA setup with synchronous replication (ADR 0014 D5): sustained and
   peak transactions per second, p95/p99 write latency, lock wait, deadlock rate, outbox lag, reporting latency.
3. Also measured: I-4 and guard trigger cost, snapshot foreign key cost, `NUMERIC` cost, RLS cost, `FOR KEY
   SHARE` MultiXact on hot system anchors, synchronous-commit latency.
4. D7's timeouts and caps are set from these results.

## Consequences
- ADR 0008's balance update and outbox steps return. Writers contend only on the positions they touch.
- A tenant-wide order can be added later as a new field; a promise made now could never be removed.
- A slow or hot tenant cannot hold every pool connection.
- Proto follow-up: `sequence_no` fields removed; comments that call `posted_at` the commit time change.

## Needs benchmark or human verification
- Cost of `trg_lp_balanced`, `trg_lp_guard` and the snapshot foreign key per posting.
- One lock statement per position versus one statement for all.
- Late update of hot system positions with `UPDATE … RETURNING` (review §11.1): needs a benchmark and an
  independent no-deadlock proof; not adopted.
- Server retry only for `40P01`, at most 2: benchmark. `transaction_timeout` (PostgreSQL 17+): unverified.
- Concurrency tests: same-position debits and credits, A↔B, concurrent reversal, duplicate keys.

## Open items
- Numeric TPS and latency targets (owner: product and operations; assumption: none; needs: the measurements above).
- Timeout and cap values (D7; needs: the measurements above).
- Team staffing and on-call coverage (owner: engineering management; ADR 0014).

## Alternatives rejected
- **Insert-only running balances (old 0020).** Runtime user with `INSERT`/`SELECT` only, the stored balance as the
  last posting's `balance_after`, no balance table, account state as insert-only closure rows. Lost because it
  has no place for reservations (holds) and PostgreSQL cannot lock a row without `UPDATE` (review §19.1, REJECT).
  Kept from it: `balance_after` on every posting, the lock comparator, a journal without `UPDATE`, domain times
  from the `Clock` port.
- **Tenant-wide sequence (I-18 as first written), by tenant lock or by "safe horizon".** Serialises commits per
  tenant, or needs in-flight tracking; no measured requirement.
- **Order by timestamp or time-ordered id.** Same skipped-row problem, plus clock skew.
- **Optimistic versions with retry (Sarv).** Conflicts grow on hot accounts.
- **Full event sourcing.** Journal plus history tables already allow audit and rebuild.
- **Invent capacity numbers now.** They would be copied into code and never revisited.
- **Server-side hot-account relief (batching, sharded balances) in v1**; **`NOWAIT` locks** (turns a short wait
  into client retries).
