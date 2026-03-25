# ADR 0021: Ownership boundary, wallets and buckets, wallet states, system and exceptional operations, provisioning saga, payment-timeline boundary

Status: Proposed · Date: 2026-11-15 · Amends 0005, 0010, 0011, 0013

Replaces earlier drafts: old 0035, old 0036, old 0042; old 0025 (superseded, see Alternatives).

## Context
Review §1.1, §4, §4.1, §4.2, §7.1, §7.3 (`wallets`, `wallet_status_events`, `balance_buckets`,
`bucket_status_events`, `wallet_provisioning`), §8.1, §8.4, §9.3, §10, §13.1, §19. Same-day revisions: the first
drafts kept multi-asset accounts, account-id supply, the system-account rule, the number of wallets per owner,
suspended credits, overdraft and the product catalog open; the owner decided them (below).

**Owner decisions (2026-11-15), ownership and mapping:**
- Ledger owns Wallets and BalanceBuckets. Accounts, account classification and account lifecycle belong to the
  external Account Service. Payments owns payment intent, provider attempts and the timeline.
- The Account Service supplies and owns external account ids. Ledger owns the Bucket and the Bucket-to-Account
  association. Ledger never invents an external account id and never creates an Account entity.
- Customer balances: one Bucket = one asset = one external account reference for that asset. No pooling of
  different customers' balances. Financial balance identity is `(tenant_id, external_account_id, asset_code)`.
  System accounts may be pooled where rules explicitly permit.
- One external customer account may back several assets if the Account Service declares them (example: `acc_123`
  supports IRR, USD, USDT). Each asset has its own Bucket and balance. No cross-asset netting. By default one
  customer Bucket per account and asset; several Wallets must not create duplicate ownership of the same account
  and asset (unique constraint; system accounts excluded). If the Account Service does not support multi-asset
  accounts, it provisions a separate account id per asset. (The review had recommended N:1 per customer; the
  owner's choice removes the omnibus seal and anchor problems for customers.)

**Owner decisions (2026-11-15), wallets:**
- Several Wallets per owner. No fixed database maximum; product restrictions are configurable business policy. A
  Wallet may have several Buckets, normally one per supported asset.
- States: `PROVISIONING` (D3), `ACTIVE`; `SUSPENDED` blocks customer-initiated outgoing operations and new Holds,
  and accepts authorized system-originated credits, refunds, settlement corrections and explicitly permitted
  inbound operations; `FROZEN` is a hard restriction: normal debits, credits and Hold captures are blocked, only
  explicitly authorized legal or corrective operations run; `CLOSING` is a closure workflow in progress; `CLOSED`
  takes no normal financial operation.
- Release of an existing Hold is always possible. A pre-existing Hold may be captured while `SUSPENDED` only when
  an explicit policy permits completing an already-authorized operation. `FROZEN` never allows ordinary capture.
- Every exceptional operation is authorized and auditable (D5).

**Owner decision (2026-11-15), provisioning:** trusted request → Ledger creates the Wallet in `PROVISIONING` →
Ledger asks the Account Service for account references outside any Ledger database transaction → the Account
Service creates or resolves accounts and returns stable ids, supported assets and versioned policies → Ledger
records the bucket-account associations → Ledger activates the Wallet only after all required mappings succeed.
Idempotent and recoverable; partial failure handled by a saga; no 2PC.

**Owner baseline (payments):** Payments owns Payment Intent; Payments/Integration owns Provider Attempts;
Payments/Operations owns the Payment Operational Timeline; never auto-release a hold because a provider outcome
is unknown. Sarv evidence (review §10.1): HTTP logs are best-effort, dropped under pressure, linked to payments only
by `CorrelationId`, kept 30 days; one payment `Status` mixes payment, bank and ledger state.

## Decision

Reading guide: D1–D2 ownership and mapping; D3 provisioning; D4 lifecycle and operation matrix; D5 authorization of
system and exceptional operations; D6 payment-timeline boundary. D4 uses the `PROVISIONING` state of D3 and the
origins of D5, and D1.1 and D6 are one ownership boundary, so the sections are read together.

### D1. Ownership boundary (old 0035)
1. Ledger: Wallet, BalanceBucket, Bucket-to-Account association, Hold / Reservation, Journal Transaction, Posting.
   External Account Service: Account, account id, classification, lifecycle, overdraft policy. Payments Service:
   Payment Intent. Payments/Integration: Provider Attempt. Payments/Operations: Payment Operational Timeline.
2. Ledger has **no authoritative account table and no account lifecycle API** (no `CreateAccount`, `GetAccount`,
   `ListAccounts`, `CloseAccount`, `Account` message, scopes `accounts:write`, `accounts:read`,
   `accounts:overdraft`). It keeps the non-authoritative cache and immutable snapshots of ADR 0022 and offers
   `ApplyAccountState` to the Account Service only.
3. The Account Service's classification states `account_scope` (`CUSTOMER` or `SYSTEM`) and `account_class`.
   Ledger stores both from the snapshot; `account_scope` is immutable (trigger) and every bucket and position
   repeats it, checked by a foreign key to the cache. The five account types of ADR 0005 are no longer a Ledger
   enum; `normal_side` comes from the snapshot.
4. Account ids and who may name them:
   - (a) Ledger never creates or binds an account on a client's say-so. Bucket-to-account associations are created
     only from Account Service output: the provisioning saga (D3) or an equivalent trusted call.
   - (b) A posting may name a system account by `external_account_id` as a target (ADR 0016 D1.2). Ledger accepts it
     only if the id resolves to an account of scope `SYSTEM`, from the cache or `BatchGetAccounts` (ADR 0022 D3, ADR
     0017 D5 steps 4, 5 and 8). Otherwise the error is `ACCOUNT_UNKNOWN`.
   - (c) A customer account id is never a posting target. Customers are addressed by bucket, or by wallet plus asset.
   - (d) Any client with `transactions:write` may post to any system account of its tenant. This is the accepted
     residual risk of ADR 0020 D5.3.

### D2. Bucket-to-account mapping and balances (old 0035)
1. Wallet → Bucket 1:N; Bucket → asset N:1 and immutable; Bucket → `external_account_id` immutable. A bucket's
   `(external_account_id, asset_code)` must be an asset the account declares (foreign key to `account_asset_state`).
2. **Customer:** unique index `ux_bucket_customer_balance (tenant_id, external_account_id, asset_code) WHERE
   account_scope = 'CUSTOMER'`, over all statuses. A closed bucket keeps its account and asset for ever; a new
   bucket needs a new account reference. So customer balance identity equals exactly one bucket position.
3. **System:** postings go to a direct position per `(external_account_id, asset_code)` (a `CHECK` forbids direct
   positions for customer accounts). A system account may also back several buckets where a rule explicitly
   permits pooling (excluded from the unique index).
4. A customer account's balance per asset = its one bucket position; a wallet's = Σ its bucket positions; a system
   account's = Σ its positions. None is stored. Unlike assets are never summed without a valuation rate (ADR 0025 D4).

### D3. Provisioning saga (old 0035)
1. **Start** (`CreateWallet`, scope `wallets:write`, idempotency key; caller is a trusted onboarding service): one
   transaction inserts the wallet (`PROVISIONING`), its status event, a `wallet_provisioning` row (`REQUESTED`,
   requested assets, `account_request_key` derived from tenant and wallet id), the idempotency record and
   `WalletProvisioningStarted`. The response returns the wallet in `PROVISIONING`.
2. **Request accounts** (no database transaction open): `ProvisionAccounts(account_request_key, owner_ref,
   wallet_id, assets[])` on the Account Service, idempotent on the key. State → `ACCOUNTS_REQUESTED`. It returns,
   per asset, the account id, scope, class and versioned policy, or an error.
3. **Map** (one transaction): upsert cache and snapshots (greater version only) → ① anchors `FOR KEY SHARE` → ②
   wallet `FOR UPDATE` → insert buckets and positions (unique customer index, supported-asset and scope foreign
   keys) → state `MAPPED`.
4. **Activate** (same transaction as 3 when every requested asset is mapped): wallet → `ACTIVE`, state `ACTIVATED`,
   `WalletActivated` in the outbox. Until then the wallet takes no posting or hold (`trg_lp_guard`, smoke S13; I-34).
5. **Retry.** A Ledger worker resumes rows by `next_attempt_at` with the same `account_request_key`. Every step is
   idempotent: an existing bucket for the same wallet, account and asset is success; a customer account and asset
   already bound to another wallet is a terminal `ACCOUNT_ALREADY_BOUND`.
6. **Fail.** On a terminal error or retry exhaustion: state `FAILED` with `last_error_code`, wallet `CLOSED` with
   reason `PROVISIONING_FAILED` (it never held money), buckets created so far closed, `WalletProvisioningFailed`
   published so the Account Service can release or reuse its accounts. No 2PC.
7. Adding a bucket to an active wallet later (new asset) follows steps 2 and 3 for one asset.
   `wallet_provisioning` and `wallet_provisioning_events` hold the saga state and history.

### D4. Wallet and bucket lifecycle (old 0036)
1. **Tables.** `wallets` (`PROVISIONING, ACTIVE, SUSPENDED, FROZEN, CLOSING, CLOSED`) and `balance_buckets`
   (`ACTIVE, SUSPENDED, FROZEN, CLOSED`) hold current state; `wallet_status_events` and `bucket_status_events` are
   append-only history with `reason_code`, `approval_ref`, idempotency key, client and correlation ids. Rows are
   never deleted. Identity columns are immutable by trigger (I-23).
2. **Several wallets per owner.** `owner_ref` is opaque, no PII (extends ADR 0011); indexed, not unique. Limits
   per owner or wallet type are business policy in configuration (`WALLET_LIMIT_EXCEEDED`).
3. **Effective state** of a bucket = the most restrictive of wallet and bucket state, in the order `CLOSED`,
   `PROVISIONING`, `FROZEN`, `CLOSING`, `SUSPENDED`, `ACTIVE`. Account restrictions (ADR 0022) apply on top.
4. **Operation matrix** (origin per D5; "in" = posting in the account's normal direction, "out" = the opposite):

| Effective state | CLIENT in | CLIENT out | SYSTEM in | SYSTEM out | EXCEPTIONAL | New hold | Capture | Release |
|---|---|---|---|---|---|---|---|---|
| ACTIVE | yes | yes | yes | yes | yes | yes | yes | yes |
| SUSPENDED | with policy | no | yes | no | yes | no | with policy | yes |
| FROZEN | no | no | no | no | yes | no | no (exceptional only) | yes |
| CLOSING | no | no | yes | yes | yes | no | no | yes |
| PROVISIONING, CLOSED | no | no | no | no | no | no | no | yes |

   "With policy" = the server attaches a configured `policy_ref` (never a client flag). `ledger-core` enforces the
   matrix; `trg_lp_guard` enforces the posting columns again (smoke S10, S11, S13). Holds do not post, so new-hold
   and release rules are application-level.
5. **Primary bucket.** At most one open primary bucket per `(wallet, asset)`; a wallet-plus-asset target resolves
   to it. It cannot close while the wallet is open (`PRIMARY_BUCKET_NOT_CLOSABLE`), except by a successful account
   seal, which closes every bucket of the closed account (ADR 0022 D6).
6. **Operations** (scope `wallets:write`, idempotency key; locks per ADR 0017 D4):

| Operation | Preconditions | Locks |
|---|---|---|
| CreateWallet | trusted request; starts D3 | none beyond inserts |
| AddBalanceBucket | wallet `ACTIVE` or `PROVISIONING`; account id and asset from the Account Service; D2 rules | ① anchor → ② wallet |
| Suspend / Activate | `ACTIVE` ↔ `SUSPENDED` | ② wallet → ③ its positions, sorted |
| Freeze / Unfreeze | scope `wallets:freeze` and `approval_ref` (D5) | ② → ③ |
| StartClosing | from `ACTIVE` or `SUSPENDED` | ② → ③ |
| CloseBalanceBucket | `posted_balance = 0`, `reserved_amount = 0`, `active_hold_count = 0` | ② → ③ |
| CloseWallet | `CLOSING`; every bucket position zero with no holds → else `WALLET_BALANCE_NOT_ZERO` | ② → ③ all |

7. **`CLOSED` is terminal** (I-24). A failed provisioning ends in `CLOSED` with reason `PROVISIONING_FAILED`.
8. **Local linearisation.** Postings read wallet and bucket state after taking the position lock ③; every state
   change holds the locks of all affected positions. No cross-service fence for wallet state (review §9.3).
9. Overdraft on a bucket follows the account's versioned policy (ADR 0022 D8); default 0. Local freeze replaces the
   operator freeze table of the first drafts for wallets and buckets (ADR 0025 D5). `product_code` (for example
   `MAIN`, `VOUCHER`, `REWARD`) is validated against configurable business policy; the catalog is configuration.
10. **Error codes** (ADR 0019 D2): `WALLET_NOT_FOUND`, `BUCKET_NOT_FOUND`, `WALLET_NOT_ACTIVE`, `BUCKET_NOT_ACTIVE`,
    `WALLET_FROZEN`, `WALLET_BALANCE_NOT_ZERO`, `BUCKET_BALANCE_NOT_ZERO`, `PRIMARY_BUCKET_EXISTS`,
    `PRIMARY_BUCKET_NOT_CLOSABLE`, `WALLET_LIMIT_EXCEEDED` (policy), `ACCOUNT_ALREADY_BOUND` (saga).

### D5. Authorization of system-originated and exceptional operations (old 0036)
**Owner decision (2026-11-15):** every exceptional operation is authorized and auditable.
1. **Origin is never a request field.** The server derives `origin` from the token: `CLIENT` for any caller with
   `transactions:write` (or `holds:write` for captures); `SYSTEM` only for identities holding `transactions:system`,
   granted to named services (refund, settlement, provisioning workers), for system-originated credits, refunds and
   settlement corrections; `EXCEPTIONAL` only for identities holding `transactions:exceptional`, and only with an
   `approval_ref` issued by an approval workflow outside the requesting service (four-eyes) and a `reason_code`. A
   `CHECK` requires both (smoke S11b; I-35).
2. `policy_ref` (for example "inbound transfer to a suspended wallet allowed", or a hold policy with
   `capture_when_suspended`) is resolved by the server from configured, versioned policy; a client cannot send it.
3. Freeze and unfreeze need scope `wallets:freeze` plus `approval_ref`; the status event stores both.
4. **Audit.** `ledger_transactions` stores `origin`, `policy_ref`, `reason_code`, `approval_ref`, `client_id`,
   `request_id`, `correlation_id`; status and hold events store `approval_ref`, client and correlation ids.
   Reconciliation reports every `EXCEPTIONAL` transaction per run.
5. `PERMISSION_DENIED` for a missing scope; `APPROVAL_REQUIRED` for a missing or unknown `approval_ref`.

### D6. The payment timeline lives in Payments and Operations, not in Ledger (old 0042)
1. Ledger stores no provider, HTTP, callback or raw-payload data, and no per-payment status; Payments derives its
   ledger status from Ledger events. Payments sends `external_ref = payment_id` (opaque) and `x-correlation-id`.
2. For correlation Ledger exposes `external_ref` on transactions and holds (indexed), with
   `ListTransactionsByExternalRef` and an `external_ref` filter on `ListHolds`; `transaction_id` and `hold_id` in
   every response; events on `ledger.transactions.v1`, `ledger.holds.v1`, `ledger.settlement.v1`,
   `ledger.reconciliation.v1` with `correlation_id` and those ids (ADR 0024 D1).
3. The Operations read model joins Payments and Ledger events by `external_ref`, `correlation_id`,
   `ledger_transaction_id` and `hold_id`; it is read-only and rebuildable from Kafka.
4. With an `UNKNOWN` provider outcome, Payments keeps the hold and resolves by inquiry or reconciliation; it does not
   call release, and selects a hold policy whose TTL covers the inquiry window (ADR 0023 D4). The Payments data
   model of review §10.3 is a proposal for the Payments service, not decided here.

## Consequences
- Seal for a customer account locks one position per asset (ADR 0022 D6); no shared customer anchor. Hot positions
  remain on system accounts (ADR 0017 D7). A second wallet of the same owner needs its own account reference per
  asset; the provisioning request carries the wallet id so the Account Service can allocate per wallet.
- `external_account_id` must be opaque (no real account number or IBAN): contract requirement on the Account Service.
- Suspending or freezing a wallet locks all its positions; a wallet with many buckets holds a longer lock.
  `wallets:freeze` is a separate, rarely granted scope.
- Ledger stays free of provider payloads; Payments needs its own ADRs for timeline tables, redaction, encrypted
  payload storage and access logging. Smoke checks S02–S06 (review §2.2) exercise D1.3 and D2.

## Needs benchmark or human verification
- Concurrency tests: state change concurrent with posting, close concurrent with hold creation.
- Cost of `trg_lp_guard` per posting (ADR 0017, capacity section).

## Open items
- Legal retention periods and raw payment payload retention (owner: legal and DPO; assumption: Ledger keeps none;
  needs: regulator requirements).
- Team staffing and on-call coverage for the Operations read model (owner: engineering management).

## Alternatives rejected
- **Ledger-owned accounts (old 0025).** Unique account `external_ref`, `ListAccounts`, `CloseAccount` with a zero
  balance and an insert-only closure row, required immutable `allow_negative_balance`, `AccountStatus` with
  `FROZEN`, no `metadata`, no `ListEntries`. Lost because accounts belong to the Account Service (owner decision
  2026-11-15). Kept for wallets and buckets: creation under an idempotency key, close only at zero with no hold,
  `CLOSED` terminal, immutable identity, no `metadata` (`owner_ref` opaque). Insert-only closure rows are replaced by
  a status column plus append-only `*_status_events`, since PostgreSQL row locks need `UPDATE` anyway (ADR 0014).
- **N:1 per customer, omnibus pooling of customer balances.** Rejected by the owner.
- **Account Service owning wallets, or wallet state in the Account Service.** Wallets are Ledger's; a local lock is
  race-free.
- **One open wallet per owner (first draft, Sarv).** Rejected by the owner.
- **Timeline inside Ledger.** Puts provider payloads, which may contain personal data, next to an immutable journal
  (ADR 0011). **Correlation by `correlation_id` only (Sarv)** is not enough to find one payment's effects.
- **Split this ADR (considered 2026-11-15, kept as one).** Measured: 216 lines, of which about 38 are the
  owner-decision records in the Context (lines 13 to 50), 47 are D4 and 13 are D6. D4 depends on D3 (the
  `PROVISIONING` state) and D5 (the origin columns of its matrix); D1.1 and D6 state one ownership boundary (Ledger,
  Account Service, Payments, Operations). Outside this file D1 is cited 11 times, D4 8 times, D5 9 times (11 counting
  list forms such as "D4, D5") and D6 twice, so a split of D4 and D5 means renumbering and rewriting about 17 to 19
  references for a readability gain that the reading guide gives at no cost. D6 alone could move (two outside
  citations), but it is the same boundary as D1.1, so moving it would split one decision. Revisit if
  Payments/Operations timeline tables need decisions on the Ledger side, or if D4/D5 grow further (for example
  per-wallet posting rights, ADR 0020 D5.3). Then move D4 and D5 together into the next free ADR number (0027), keep a
  one-line pointer in D4 and D5 here, and rewrite the citations by script.
