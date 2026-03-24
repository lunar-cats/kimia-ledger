# ADR 0016: Entry model: effective time, server-built reversals, repeated accounts, input message

Status: Proposed · Date: 2026-11-14 · Amends 0005, 0007

## Context
Review findings B-3, B-4, B-5, D-6, D-13; audit finding R-17. Checked against the draft contract and
`invariants.md`:
- An entry has only `posted_at`. There is no business date. Rows are immutable, so it cannot be added later.
- To reverse, the caller rebuilds the mirror and sends `reverses_entry_id`. The server already has the original.
- `Posting` is used for input and output; output-only fields are "ignored" on input.
- The same account twice in one entry is undefined.
- I-2 calls the posting limit configurable. The contract publishes a fixed `2..100`.
- "Reversed at most once" has no database backing in any document.

## Decision

### Effective time (B-3)
1. `PostEntryRequest` gets an optional `effective_at` (timestamp). `Entry.effective_at` is always set. If the
   caller sends none, it equals `posted_at`. `Posting` repeats it, so a statement needs no second call.
2. `effective_at` is the business time the caller asserts. The ledger stores it and never acts on it. It does
   not affect balances, I-11, locking or order. Balances are always "as posted".
3. Bounds: at most 5 minutes after `posted_at` (clock skew), and not older than the deployment's back-dating
   limit (proposed default: 366 days). Precision is microseconds. A malformed timestamp is `INVALID_ARGUMENT`.
   A well-formed value outside the bounds is `EFFECTIVE_TIME_OUT_OF_RANGE` (I-27). It depends on the clock,
   so it is recorded under the idempotency key (ADR 0017).
4. v1 has no period close. Within the bounds, nothing stops back-dating.

### Reversal (B-4)
5. New RPC `ReverseEntry(entry_id, idempotency_key, effective_at?, external_ref?)`. Scope `entries:write`.
6. The server builds the reversal from the original: same accounts, amounts, currencies and order; every
   direction flipped. `Entry.reverses_entry_id` is output-only. The original shows `reversed_by_entry_id`,
   derived on read; the original row is not updated.
7. `reverses_entry_id` leaves `PostEntryRequest`. A mirror posted by hand is an ordinary entry with no link.
8. An entry is reversed at most once → `ENTRY_ALREADY_REVERSED`. A reversal cannot be reversed →
   `ENTRY_NOT_REVERSIBLE`. There is no partial reversal.
9. "At most once" is backed by the database: an insert-only link row `(tenant_id, original_entry_id,
   reversal_entry_id)` with primary key `(tenant_id, original_entry_id)`, written in the reversal's
   transaction (R-17). A separate table avoids a unique index over a nullable column; Oracle's handling of
   partly-null composite unique keys is not relied on (unverified, not needed).
10. **A reversal gets no exemption.** It is validated like any entry. A closed account → `ACCOUNT_CLOSED`
    (I-7). A frozen account → `ACCOUNT_FROZEN` (I-28). A balance that would go below zero where that is not
    allowed → `INSUFFICIENT_FUNDS` (I-11).
11. If a reversal is refused, the correction is an adjusting entry on open accounts, posted with `PostEntry`.

### Same account more than once (D-6)
12. Allowed. The account is locked once. Each posting gets its own `account_seq` and `balance_after`
    (ADR 0014).
13. I-11 is checked on the balance after the account's last posting in the entry. An intermediate
    `balance_after` inside one entry may be lower; statements must show it as part of one entry. Every
    `balance_after` must fit the money range → `BALANCE_LIMIT_EXCEEDED` (ADR 0019, I-26).
14. An entry must touch **at least two distinct accounts** → `INVALID_ENTRY` (I-22).

### Input message (B-5) and limits (D-13)
15. `PostEntryRequest.postings` is a list of `PostingInput { account_id, direction, money }`. `Posting` is
    output only.
16. `MAX_POSTINGS_PER_ENTRY = 100` is a constant of `ledger.v1`. It is not configurable. The same holds for
    every limit the contract publishes. Raising a limit is an additive change; lowering one is breaking
    (ADR 0032). The back-dating limit in decision 3 is deployment policy, not a contract limit.

## Consequences
- I-14 holds by construction. The "mirror must match" check disappears.
- `CLOSED` is terminal and a closed account always has a zero balance (ADR 0025).
- The original is read without a lock, because it is immutable. Its accounts are then locked in I-17 order,
  and "already reversed" is checked under those locks. The link row's key is the backstop; its violation maps
  to `ENTRY_ALREADY_REVERSED`.
- A wrong `effective_at` is corrected by reversing and posting again.
- Callers must be told that the back-dating limit exists and is set per deployment.

## Alternatives rejected
- **A calendar date instead of a timestamp.** No time-zone question, but it cannot carry a time. A timestamp
  can carry a date.
- **Reversal succeeds on a closed account (review).** It would put a balance into an account that can no
  longer be posted to. The money could not be moved out, and "closed means zero" would be false.
- **Reversal ignores I-11.** One rule set for all entries is simpler to state and to test.
- **Reversal of a reversal.** It creates chains in which "reversed at most once" has no clear meaning. The
  caller can post the original content again.
- **Forbid repeated accounts.** It forces callers to net lines and lose detail (principal and fee).
- **I-11 on every running balance, including inside an entry.** Simpler to verify per row, but acceptance
  would then depend on the order the caller lists the postings.

## Relation to the review and the audit
Agrees with B-3, B-5, D-6, D-13 and most of B-4. **Differs on B-4**: the review lets a reversal post to an
account closed after the original. Decision 10 refuses it, for the reason above. Adds decisions 2, 3, 9, 13,
14 and `ENTRY_NOT_REVERSIBLE`. Accepts R-17 (reversal key).
