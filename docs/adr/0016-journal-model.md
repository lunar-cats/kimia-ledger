# ADR 0016: Journal model: transactions, postings, effective time, reversals, refunds and adjustments

Status: Proposed · Date: 2026-11-15 · Amends 0005, 0007

Replaces earlier drafts: old 0016 (first written 2026-11-14 as "Entry model"; revised here for the decisions of
2026-11-15).

## Context
Review findings B-3, B-4, B-5, D-6, D-13; audit finding R-17; review §7.3 (`ledger_transactions`), §8.3, §13.1,
§19.1. Checked against the draft contract and `invariants.md`:
- An entry had only `posted_at`. There was no business date. Rows are immutable, so it cannot be added later.
- To reverse, the caller rebuilt the mirror and sent `reverses_entry_id`. The server already has the original.
- `Posting` was used for input and output; output-only fields were "ignored" on input.
- The same account twice in one entry was undefined.
- I-2 called the posting limit configurable. The contract publishes a fixed `2..100`.
- "Reversed at most once" had no database backing in any document.

The review (§19.1) kept effective time, server-built reversals, repeated targets and `PostingInput`, and asked
for: `Entry` renamed `Transaction`; a posting target that is a bucket, a wallet's primary bucket or a system
account (ADR 0021 D2); the reversal link table replaced by a partial unique index; `REFUND` and `ADJUSTMENT`;
"closed" to include closed wallets and buckets.

## Decision

### D1. Transactions and posting input
1. `Entry` is renamed **`Transaction`** in the contract (`PostTransaction`, `GetTransaction`,
   `ReverseTransaction`). `ledger_transactions.transaction_type ∈ {STANDARD, REVERSAL, REFUND, HOLD_CAPTURE, FX,
   SETTLEMENT, ADJUSTMENT, OPENING_BALANCE}`, with `CHECK`s tying `REVERSAL` to `reverses_transaction_id`,
   `HOLD_CAPTURE` to `hold_id`, `FX` to `fx_quote_id`, `REFUND` to `related_transaction_id` and `ADJUSTMENT` to
   `approval_ref` (review §7.3).
2. `PostTransactionRequest.postings` is a list of `PostingInput { target, direction, money }`; `target` is a
   `oneof` of `bucket_id`, `wallet_primary { wallet_id, asset_code }` or `external_account_id` (system accounts
   only). `Posting` is output only (`position_id`, `bucket_id`, `wallet_id`, `external_account_id`,
   `position_seq`, `account_version`, `balance_after`).
3. `MAX_POSTINGS_PER_ENTRY = 100` is a constant of `ledger.v1`. It is not configurable. The same holds for every
   limit the contract publishes. Raising a limit is an additive change; lowering one is breaking (ADR 0019 D3).
   The back-dating limit in D2.3 is deployment policy, not a contract limit.
4. A transaction must touch **at least two distinct positions** → `INVALID_ENTRY` (I-22).
5. `GetTransaction` returns postings in the order submitted. A line number is stored with each posting.

### D2. Effective time (B-3)
1. `PostTransactionRequest` gets an optional `effective_at` (timestamp). `Transaction.effective_at` is always set.
   If the caller sends none, it equals `posted_at`. `Posting` repeats it, so a statement needs no second call.
2. `effective_at` is the business time the caller asserts. The ledger stores it and never acts on it. It does
   not affect balances, I-11, locking or order. Balances are always "as posted".
3. Bounds: at most 5 minutes after `posted_at` (clock skew), and not older than the deployment's back-dating
   limit (proposed default: 366 days). Precision is microseconds. A malformed timestamp is `INVALID_ARGUMENT`.
   A well-formed value outside the bounds is `EFFECTIVE_TIME_OUT_OF_RANGE` (I-27). It depends on the clock,
   so it is recorded under the idempotency key (ADR 0018).
4. v1 has no period close. Within the bounds, nothing stops back-dating.
5. `posted_at` is the application clock (ADR 0026 D1), read inside the write transaction. It is not the commit
   instant and not monotonic. Nothing orders or pages by a timestamp (ADR 0017 D2).

### D3. Reversal (B-4)
1. RPC `ReverseTransaction(transaction_id, idempotency_key, effective_at?, external_ref?)`, scope
   `transactions:write`.
2. The server builds the reversal from the original: mirror postings on the **same positions**, same amounts,
   assets and order, every direction flipped. `reverses_transaction_id` is output-only and leaves the post
   request; a mirror posted by hand is an ordinary transaction with no link. The original shows
   `reversed_by_transaction_id`, derived on read; the original row is not updated.
3. A transaction is reversed at most once → `TRANSACTION_ALREADY_REVERSED`. A reversal cannot be reversed →
   `TRANSACTION_NOT_REVERSIBLE`. There is no partial reversal.
4. "At most once" is backed by the database: partial unique index `ux_ltx_single_reversal` on
   `(tenant_id, reverses_transaction_id) WHERE reverses_transaction_id IS NOT NULL` (R-17). It replaces the
   insert-only link table of the first draft, which existed only to avoid relying on Oracle's handling of
   partly-null unique keys.
5. The original is read without a lock, because it is immutable. Its positions are then locked in the I-17 order
   (ADR 0017 D4), and "already reversed" is checked under those locks. The unique index is the backstop; its
   violation maps to `TRANSACTION_ALREADY_REVERSED`. Concurrent reversals of the same original serialise on the
   same positions.
6. **A reversal gets no exemption.** It is validated like any transaction: a closed wallet, bucket or external
   account, a frozen wallet or a restricted account, or a balance that would leave effective available below
   zero (I-11) is refused with the usual code (ADR 0017 D1, ADR 0021 D4, ADR 0022).

### D4. Refunds and adjustments
1. If a reversal is refused, the correction is an approved `ADJUSTMENT` (with `approval_ref`; `EXCEPTIONAL`
   origin when a restricted wallet is involved, ADR 0021 D5).
2. A partial refund is a `REFUND` transaction with `related_transaction_id`. The refund ceiling (Σ of refunds
   against an original) is a Payments policy, not a ledger rule.
3. A wrong `effective_at` is corrected by reversing and posting again.

### D5. The same position more than once (D-6)
1. Allowed. The position is locked once. Each posting gets its own `position_seq` and `balance_after`
   (ADR 0017 D2), consecutive in the order submitted.
2. I-11 is checked on the balance after the position's last posting in the transaction. An intermediate
   `balance_after` inside one transaction may be lower; statements must show it as part of one transaction.
   Every `balance_after` must fit the money range → `BALANCE_LIMIT_EXCEEDED` (ADR 0015, I-26).

## Consequences
- I-14 holds by construction. The "mirror must match" check disappears.
- `CLOSED` is terminal and a closed wallet or bucket always has a zero balance (ADR 0021 D4).
- Callers must be told that the back-dating limit exists and is set per deployment.
- Proto follow-up: `entry.proto` → `transaction.proto` (review §13.1).

## Open items
- None of its own. The back-dating default (366 days) is accepted with this ADR.

## Alternatives rejected
- **A calendar date instead of a timestamp.** No time-zone question, but it cannot carry a time. A timestamp
  can carry a date.
- **Reversal succeeds on an account closed after the original (review B-4).** It would put a balance into a
  position that can no longer be posted to. The money could not be moved out, and "closed means zero" would be
  false.
- **Reversal ignores I-11.** One rule set for all transactions is simpler to state and to test.
- **Reversal of a reversal.** It creates chains in which "reversed at most once" has no clear meaning. The
  caller can post the original content again.
- **Insert-only reversal link table (old 0016 decision 9).** Needed only on Oracle; a partial unique index is
  simpler on PostgreSQL.
- **Forbid repeated targets.** It forces callers to net lines and lose detail (principal and fee).
- **I-11 on every running balance, including inside a transaction.** Simpler to verify per row, but acceptance
  would then depend on the order the caller lists the postings.
