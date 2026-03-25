# ADR 0023: Native holds on bucket positions, versioned hold policies, append-only hold_events

Status: Proposed · Date: 2026-11-15 · Relates to 0017, 0019, 0021

Replaces earlier drafts: old 0039; old 0022 (superseded, see Alternatives).

## Context
Review finding D-2; review §7.3 (`hold_policies`, `holds`, `hold_events`), §8.5, §11.1, §13.1, §14.1. Card-style
flows authorise an amount, then capture or void it. Same-day revision: over-capture, capture under restriction and
TTL were open in the first draft; the owner decided them. TTL values come from approved hold policies.

**Owner decisions (2026-11-15):** operations are create, partial capture, full capture, partial release, full
release, expiration and authorized cancellation. Over-capture is rejected; capture plus release cannot exceed
the reserved amount. Each Hold belongs to one Wallet Bucket and one asset. Every transition is recorded in
immutable `hold_events`. Hold state and reserved balance change atomically in PostgreSQL. Expiration is done by
a Ledger-owned worker. Every mutation has an idempotency key. Maximum TTL is configurable per product policy; no
universal TTL is invented; exact default and maximum values are configured and approved before production.
Release is always possible; capture while `SUSPENDED` only with an explicit policy; never while `FROZEN`
(except an approved exceptional operation, ADR 0021 D5); never auto-release because a provider outcome is unknown.

## Decision

### D1. Placement and policies
1. A hold is on exactly one bucket position (one wallet, one asset). A `CHECK` forbids reservations on direct
   (system) positions.
2. `hold_policies(policy_code, policy_version, default_ttl, max_ttl, capture_when_suspended, approval_ref)` is
   append-only and versioned per tenant. A hold records the policy version it was created under.
   `expires_at ≤ created_at + max_ttl` and the default TTL are checked by the application. No TTL value is defined
   in code.

### D2. State and history
`holds` holds current state (`ACTIVE, CAPTURED, RELEASED, EXPIRED`, `amount`, `captured_amount`,
`released_amount`); `CHECK (captured_amount + released_amount <= amount)` makes over-capture and over-release
impossible in the database (smoke S12; I-36). `hold_events` (`CREATED, CAPTURED, RELEASED, EXPIRED`; reasons
`CLIENT_REQUEST, FINAL_CAPTURE_REMAINDER, EXPIRY, CANCELLED`; `policy_ref`, `approval_ref`) is append-only (I-37).

### D3. Operations
Scope `holds:write`, idempotency key on every mutation; lock levels per ADR 0017 D4.

| Operation | Locks | Rule |
|---|---|---|
| CreateHold | ① anchor → ③ position | effective state `ACTIVE`; account `can_debit`; effective available ≥ amount; `reserved += a`, count `+1` |
| CaptureHold (x, targets, final) | ① anchors → ③ positions → ④ hold | hold `ACTIVE`, not expired; `x ≤ remaining` else `HOLD_AMOUNT_EXCEEDED`; `SUSPENDED` only if the hold's policy has `capture_when_suspended` (transaction gets `policy_ref`); `FROZEN` → `WALLET_FROZEN`; `HOLD_CAPTURE` transaction; final releases the rest |
| ReleaseHold (partial or full) | ③ → ④ | always allowed, in every state |
| CancelHold | ③ → ④ | full release with reason `CANCELLED`; same scope, recorded client and correlation ids |
| ExpireHolds (worker, per tenant) | ③ → ④, re-check | release the remainder at `expires_at`; key `system:expire:{hold_id}` |

Creating a hold makes no posting; only a capture makes a journal transaction. Reconciliation checks
`reserved_amount = Σ remaining of ACTIVE holds` and the count (ADR 0025 D3).

### D4. Unknown provider outcome, events and error codes
1. Ledger never releases a hold because of an outcome; only an explicit release, cancel or the policy's expiry
   does. Payments must not release a hold while the outcome is `UNKNOWN`, and must choose a hold policy whose TTL
   covers its inquiry window (ADR 0021 D6; see review §20.4 challenge on expiry).
2. Events on `ledger.holds.v1`, keyed by hold (ADR 0024 D1).
3. Error codes (ADR 0019 D2): `HOLD_NOT_FOUND`, `HOLD_NOT_ACTIVE`, `HOLD_EXPIRED`, `HOLD_AMOUNT_EXCEEDED`,
   `HOLD_POLICY_NOT_FOUND`, `HOLD_TTL_EXCEEDED`.

## Consequences
- Partial capture and release without hold accounts. The expiry worker iterates tenants, one per transaction.
- Production needs approved policy rows per product before the first hold.

## Needs benchmark or human verification
- Concurrency tests: capture versus expiry, capture versus release.
- Expiry worker lag (metric: expired but not released holds).

## Open items
- Exact hold TTL values per product (owner: product owner with Payments; assumption: none in code; needs: the
  provider inquiry window and card/rail authorization lifetimes).

## Alternatives rejected
- **No native holds in v1; a hold account per customer (old 0022).** No pending postings, hold RPCs or expiry
  timer; authorise = debit customer, credit a per-customer hold account; capture and void as ordinary entries
  (void deliberately not a reversal); expiry left to the caller. Lost because native holds on buckets are in the
  owner baseline (review §19.1, REJECT), and a hold account moves held money out of the bucket.
- **Universal default TTL in code.** Rejected by the owner.
- **Full capture only (Sarv).** Blocks unrelated debits.
