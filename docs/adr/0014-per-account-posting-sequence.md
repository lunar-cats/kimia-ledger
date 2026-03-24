# ADR 0014: Per-account posting sequence; no tenant-wide journal order

Status: Proposed · Date: 2026-11-14

## Context
Review finding B-1, audit finding R-1. I-18 promises a strictly increasing `sequence_no` per tenant over
committed entries and says pagination and event ordering use it. The draft contract carries it in
`Posting.sequence_no`, `Entry.sequence_no` and `GetBalanceResponse.as_of_sequence_no`.

A number is assigned before commit. Two transactions can commit in the opposite order to their numbers. A
reader that pages by cursor then skips the entry that committed late. The promise can only be kept by one lock
per tenant held until commit, which serialises every write of a tenant.

Checked against the draft contract: the only paginated read is `ListPostings`, and it is per account.
`GetBalance` is per account. v1 has no event stream (ADR 0021). Nothing in v1 needs a tenant-wide order.

## Decision
1. `ledger.v1` promises **no tenant-wide order**. `sequence_no` leaves `Posting` and `Entry`;
   `as_of_sequence_no` leaves `GetBalanceResponse`.
2. Every posting has an **`account_seq`** (`int64`, ≥ 1). For each account the committed postings carry exactly
   `1..n`: no gaps, no duplicates, in commit order.
3. `account_seq` is the previous posting's number plus one, read while the write path holds the account lock
   (I-17). No counter row is updated (ADR 0020). A rollback leaves no number behind.
4. The database has a unique constraint on `(tenant_id, account_id, account_seq)`. A violation is a server bug
   and surfaces as `INTERNAL`.
5. If an account appears more than once in an entry (ADR 0016), its postings get consecutive numbers in the
   order submitted.
6. `ListPostings` returns postings in ascending `account_seq`. The page token encodes the last `account_seq`
   returned and is valid only for the same account. Pages are stable: a reader never skips or repeats a
   posting. This is safe because a number is only assigned under the account lock, so no posting with a lower
   number of the same account can commit later.
7. `GetBalance` returns `as_of_account_seq` (`0` if the account has no postings) and `as_of` (the `posted_at`
   of that posting, or the account's `created_at`). Balance and number come from the same posting row.
8. Every posting row stores **`balance_after`**: the signed balance of the account (I-9) after that posting.
   It is the stored balance of the ledger (ADR 0020). It is not exposed in v1; exposing it later is additive.
9. `posted_at` is the application clock (ADR 0030), read inside the write transaction. It is not the commit
   instant. It is not monotonic, neither across accounts nor within one. Nothing orders or pages by a timestamp.
10. `GetEntry` returns postings in the order submitted. A line number is stored with each posting.
11. **Any future tenant-wide feed** (events, `ListEntries`) must not be a cursor over a number assigned before
    commit. It is either claimed by status from an outbox (ADR 0021) or reads behind a safe horizon. This
    is a constraint on later designs, not a v1 feature.

## Consequences
- Writers contend only on the accounts they touch.
- A consumer can detect a missed posting: the numbers of an account have no gaps.
- There is no read that returns "all entries of a tenant, in order". `ListEntries` is not offered in v1
  (ADR 0025).
- A tenant-wide order can be added later as a new field. A promise made now could never be removed. That is
  why the weaker promise is chosen.
- The proto comments that call `posted_at` the commit time must change.

## Alternatives rejected
- **Lock a tenant row, at the start or just before commit.** Keeps I-18 as written. Serialises commits per
  tenant, so tenant throughput is bounded by commit latency. No measured requirement justifies it.
- **Database sequence plus a "safe horizon" on reads.** Readers only see rows below the lowest in-flight
  number. Needs tracking of in-flight transactions; vendor-specific; complex.
- **Order by timestamp or by time-ordered id.** Same skipped-row problem, plus clock skew between instances.
- **Counter column on the account row, updated in place (earlier draft of this ADR).** Needs an UPDATE grant on
  money-bearing state. Rejected in ADR 0020.

## Relation to the review and the audit
Agrees with B-1 and R-1. Adds the gapless guarantee and its constraint, `balance_after`, the definition of
`posted_at`, and the rule for any later tenant-wide feed (R-1). `ListEntries` (D-5) is deferred for the same
reason.
