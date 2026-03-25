# ADR 0022: External account state: bounded-staleness cache, snapshots, lookup before BEGIN, fence, seal, overdraft policy

Status: Proposed · Date: 2026-11-15 · Relates to 0016, 0017, 0019, 0021, 0025

Replaces earlier drafts: old 0037.

## Context
Review §4.1, §7.3 (`account_state_cache`, `account_asset_state`, `account_snapshots`, `account_asset_snapshots`),
§8.1, §8.2, §9, §13.1, §14.1. Same-day revision: the first draft continued on a valid cache when the Account
Service was down, left cache age and the event schema open, and had no per-asset state; the owner decided them.

**Owner decisions (2026-11-15):** bounded-staleness local `account_state_cache` plus immutable
`account_snapshots`; the Account Service stays authoritative. `AccountStateChanged` carries `tenant_id`,
`external_account_id`, `account_version`, status, debit and credit capabilities, allowed assets, normal balance
side, overdraft permissions and limits, account classification, event id and timestamp. `account_version` is
monotonic per account; duplicates and out-of-order events are handled safely; only newer versions update the
projection; each posting records the exact version used. Maximum cache age: initial 30 s, configurable, not a
proven SLO. Fresh → use; stale → refresh with `BatchGetAccounts` before opening the database transaction; miss →
fetch; refresh needed and Account Service unavailable → **fail closed**. Never call the Account Service while
holding financial locks. Freeze, close and other restrictive permission changes use the synchronous fence
`ApplyAccountState` before the Account Service reports success; Ledger applies it under the anchor lock. Close
seal: posted balance 0, reserved 0, active hold count 0, outstanding financial restrictions resolved; if the seal
fails the Account Service must not declare the account closed. No 2PC.

**Owner decision (2026-11-15), overdraft:** overdraft is supported and disabled by default (`overdraft_limit = 0`).
It is defined by the authoritative Account Service, asset-specific, tied to a versioned account policy snapshot,
enforced atomically inside the Ledger transaction, and never supplied or increased by a client request.
Effective available = presented balance + applicable overdraft limit − outstanding reservations, using the
correct normal balance side. Ordinary overdraft is not permission for crypto short-selling or securities margin.

## Decision

### D1. Cache and snapshots
`account_state_cache` (one row per account: version, scope, class, status, `can_debit`, `can_credit`,
`normal_side`, `closed`, `validated_at`) and `account_asset_state` (one row per declared asset: `supported`,
`overdraft_limit`, version). Both are non-authoritative and never deleted; withdrawing an asset sets
`supported = false`. Every version is recorded in append-only `account_snapshots` and `account_asset_snapshots`.
Postings reference `(tenant, external_account_id, account_version)` in `account_snapshots` by foreign key
(smoke S14; I-32).

### D2. Events
Ledger consumes `AccountStateChanged` from `accounts.lifecycle.v1` (key `{tenant}:{account_id}`). One
transaction: inbox row (ADR 0024 D4), `FOR UPDATE` on the cache row, insert snapshot rows if the version is new,
update the cache only if the version is greater. Duplicate (same version) → no-op. Older version → snapshot kept
if absent, cache unchanged.

### D3. Freshness and lookup before BEGIN
Fresh = `now − validated_at ≤ tenant_settings.account_cache_max_age` (initial 30 s). For the distinct accounts of
a request: fresh → use; stale or missing → one `BatchGetAccounts(ids[])` with a short deadline **before `BEGIN`**,
upsert snapshot and cache (greater version only, `validated_at` updated on an equal version). Refresh fails →
`UNAVAILABLE`, nothing written (fail closed).

### D4. Anchor ①
Inside the transaction every operation relying on an account (posting, hold, bucket creation) takes
`FOR KEY SHARE` on its cache row; applying state takes `FOR UPDATE` (ADR 0017 D4). A lookup result is not a
guarantee: validation uses the row read under the anchor (I-28).

### D5. Fence
`ApplyAccountState(external_account_id, account_version, state)`, internal gRPC (`AccountFenceService`), scope
`accounts:fence` for the Account Service identity only, idempotent by version, stale version →
`ACCOUNT_STATE_STALE`. **Restrictive changes must use it** before the Account Service reports success: freeze,
close, removal of a debit or credit capability, withdrawal of an asset, and any reduction of an overdraft limit
(D8). Permissive changes may arrive by event only; a stale cache is then conservative. If the fence fails, the
Account Service keeps `PENDING_FREEZE` or retries.

### D6. Seal (close)
Through `ApplyAccountState` with a closed state. Under ① `FOR UPDATE` and ③ the account's positions, sorted,
Ledger checks per asset: `posted_balance = 0`, `reserved_amount = 0`, `active_hold_count = 0`, and no outstanding
restriction: no bucket backed by the account is `FROZEN`, and no unsettled settlement item or open reconciliation
discrepancy refers to its positions. Failure → `ACCOUNT_BALANCE_NOT_ZERO` or `ACCOUNT_HAS_RESTRICTIONS`; the
Account Service must not declare `CLOSED`. On success Ledger closes the buckets backed by the account in the same
transaction (reason `ACCOUNT_CLOSED`; a primary bucket of an open wallet included, since its account no longer
exists) (I-33).

### D7. Residual race, no 2PC, error codes
1. **Residual race (owned by the Account Service).** It decides a restriction at T0; the fence commits in Ledger at
   T1. Postings committed between T0 and T1 used the earlier version and are valid. After T1 nothing conflicting
   commits. Both service contracts state this.
2. **No 2PC, no validation token, no lease.**
3. Error codes (ADR 0019 D2): `ACCOUNT_UNKNOWN`, `ACCOUNT_RESTRICTED`, `ACCOUNT_STATE_STALE`,
   `ACCOUNT_HAS_RESTRICTIONS`, `ASSET_NOT_ALLOWED`; `ACCOUNT_BALANCE_NOT_ZERO` stays; `ACCOUNT_ALREADY_EXISTS`
   deprecated.
4. The operator freeze table of the first drafts is replaced by this fence (accounts) and wallet `FROZEN`
   (ADR 0021 D4); see ADR 0025 D5.

### D8. Overdraft policy
1. The limit lives in `account_asset_state.overdraft_limit` (default 0) and in `account_asset_snapshots` for every
   version; the posting's `account_version` identifies the limit used.
2. `NULL` (no limit) is allowed only for `SYSTEM` accounts (for example clearing); for a `CUSTOMER` account it is a
   contract error and the snapshot is refused.
3. Enforcement in `ledger-core` under the position lock, after the position's last posting in the transaction:
   `presented + limit − reserved ≥ 0`, presented by `normal_side` (ADR 0017 D1). The database does not enforce it
   (smoke S09 shows the formula only).
4. Increases may arrive by event (a stale cache is then conservative). **Reductions use the fence** (D5):
   otherwise a cache up to 30 s old could grant a limit the Account Service already withdrew.
5. No request field carries or raises a limit (I-30). Crypto short positions, securities margin, borrowing and
   collateral are out of scope and need their own policy and ADR.
6. Code: `INSUFFICIENT_FUNDS` when the formula fails.

## Consequences
- The Account Service is on the write path only for stale or missing entries; its outage fails only those
  operations.
- Module `ledger-account-client` (outbound gRPC adapter).
- Contract work for the Account Service: the event and `BatchGetAccounts` must carry `account_scope`,
  `account_class` and per-asset overdraft data.

## Needs benchmark or human verification
- MultiXact cost of `FOR KEY SHARE` on hot system anchors; alternative: shared/exclusive advisory locks.
- Concurrency tests: fence and seal concurrent with posting.
- Both service contracts signed off by both owners.

## Open items
- Exact Account Service freshness SLO (owner: Account Service and Ledger owners; assumption: 30 s initial;
  needs: event-lag and lookup-latency measurement under the ADR 0017 capacity benchmark).
- Where the Account Service API is defined (`BatchGetAccounts`, `ProvisionAccounts`, the `AccountStateChanged` event)
  and which module generates its stubs for `ledger-account-client` (owner: Account Service and Ledger owners;
  assumption: the Account Service owns its contract and Ledger consumes it as a pinned artifact; needs: a decision
  before `ledger-account-client` is built). `ApplyAccountState` is served by Ledger and is defined in Ledger's
  contract.

## Alternatives rejected
- **Continue on a stale cache when the Account Service is down (first draft).** Could accept a posting after a
  restriction; rejected by the owner (fail closed).
- **Synchronous lookup on every posting**, **event projection only**, **2PC**, **tokens or leases.**
