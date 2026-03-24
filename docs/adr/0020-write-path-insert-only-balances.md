# ADR 0020: Write path with insert-only running balances

Status: Proposed · Date: 2026-11-14 · Amends 0005, 0007, 0008, 0013

## Context
Audit findings R-2, R-10, R-17, and the audit's remarks on review P-2. Checked against the documents:
- ADR 0008 step 4 updates balance rows. ADR 0008's last consequence says the outbox is "the only place where
  UPDATE is allowed". ADR 0013 grants UPDATE on the outbox only. The threat model assumes an UPDATE grant on
  balances ("no UPDATE grant outside that path"). The documents contradict each other.
- The balance table is not a "journal table" by the documents' own terms (I-13 and the threat model name
  entries and postings). The audit's claim that ADR 0008 breaks the journal grant rule is therefore wrong in
  its letter, but right that an UPDATE grant on money-bearing state exists and is not decided anywhere.
- ADR 0005 says double-entry is enforced "at the database level"; the threat model lists a per-entry check
  constraint. Neither exists. An Oracle `CHECK` constraint sees one row.
- I-17 says "ascending `account_id`" without a comparator. Account ids are UUIDs. Two comparators in use
  (string order and signed 64-bit halves) give different orders.
- Oracle `SELECT … FOR UPDATE` needs only the `SELECT` object privilege; the 12c `READ` privilege does not
  allow it (secondary sources: oracle-base and a 2007 Tanel Poder article; not checked against current Oracle
  documentation; unverified, human to confirm).

## Decision
1. **No UPDATE or DELETE on any ledger table by the runtime user.** It has `INSERT` and `SELECT` only. This
   covers accounts, entries, postings, reversal links, account closures and idempotency records. The freeze
   table is `SELECT` only for the runtime user (ADR 0029).
2. **The stored balance is the last posting's `balance_after`.** Each posting row carries `account_seq` and
   `balance_after`, unique on `(tenant_id, account_id, account_seq)` (ADR 0014). There is no balance table.
3. **Account state is insert-only.** Closing inserts a closure row (ADR 0025). The account row is locked,
   never changed.
4. **Write transaction** (`READ COMMITTED`, one transaction, deadline from ADR 0026):
   1. Before the transaction: authenticate, check tenant and scope, rate limit, validate shape and the
      payload-only rules (I-1 to I-4, I-21, I-22), compute the payload hash.
   2. Read the idempotency record. If present: replay or conflict (ADR 0017). Done.
   3. Lock each distinct account with `SELECT … FOR UPDATE WAIT n`, one at a time, in the I-17 order. Missing
      → `ACCOUNT_NOT_FOUND`.
   4. Read, per account: currency, exponent, `allow_negative_balance`, closure, freeze, and the latest
      `(account_seq, balance_after)`.
   5. Validate in `ledger-core`: I-5, I-7, I-11, I-26, I-27, I-28.
   6. Accepted: insert the entry, its postings (each with `account_seq` and `balance_after`), the reversal
      link if any. Rejected with a recorded code: insert nothing of this.
   7. Insert the idempotency record with the outcome, as the last statement.
   8. Commit. Respond only after commit.
5. **Failures inside the transaction.** Duplicate idempotency key → roll back, read the winner, replay or
   conflict. Duplicate `account_seq` → roll back, `INTERNAL` (a bug). Lock wait timeout, statement timeout or
   deadlock → roll back, `CONTENTION`.
6. **Lock order comparator (I-17).** Ascending unsigned byte order of the 16-byte UUID, which equals ascending
   order of the canonical lowercase hyphenated string. `java.util.UUID.compareTo` is not used: it has compared
   the two halves as signed numbers, which gives another order (unverified for Java 25; the rule avoids
   depending on it). A test pins the comparator. Lock order is a liveness property: a violation causes a deadlock, which
   the database detects and the service maps to `CONTENTION`. It cannot corrupt a balance.
7. **One lock statement per account** in v1. A typical entry touches two to four accounts. If measurement
   shows the round trips matter, the Oracle adapter may lock all accounts in one round trip with a PL/SQL block
   that loops in the same order. `ORDER BY … FOR UPDATE` over an `IN` list is not used (review P-2).
8. **Database checks** are per row only: amount range, balance range, direction, `account_seq ≥ 1`, currency
   foreign key, composite tenant foreign keys (ADR 0027), and the unique keys above. The database does **not**
   enforce I-4 or the balance chain. `ledger-core` enforces them before insert; reconciliation verifies them
   after (ADR 0029).
9. **Reads.** `GetBalance` reads the account and its latest posting in one SQL statement, so it sees one
   committed state (Oracle statement-level read consistency). No lock.
10. **Timestamps.** Domain times (`posted_at`, `effective_at` checks) come from the application `Clock` port
    (ADR 0030). Technical columns (row insert time for operations) may default to the database clock.

## Consequences
- A compromised runtime credential can add rows but cannot change or remove any. A wrong `balance_after` can
  only be inserted, and reconciliation finds it.
- With no derived balance table, "rebuild balances" no longer exists (ADR 0029).
- Every row the write path touches can later become an Oracle immutable table (ADR 0028).
- `GetBalance` costs one index lookup on the posting table (descending on `account_seq`) instead of a primary
  key read. Measure before optimising.
- Hot-account serialisation is unchanged (ADR 0026).
- The runtime user's `SELECT` grant also allows `LOCK TABLE` on Oracle (same secondary sources). A
  compromised credential can block writes; that is a denial of service, not tampering.

## Alternatives rejected
- **Balance row updated in place, UPDATE grant on that table only.** Smaller reads. A compromised runtime
  credential could change a balance without a posting, which is the threat model's first tampering case.
- **Per-entry, per-currency totals row with a `CHECK (debits = credits)` (audit R-10, option b).** The
  application writes both the postings and the totals row, so the check proves only that the application
  agrees with itself. It adds a table and enforces nothing new.
- **Database clock for all times (audit).** One clock across instances, but untestable in `ledger-core` and
  nothing orders by time (ADR 0014). Kept for technical columns only.
- **No database clock anywhere (review P-2).** Over-restrictive; technical columns gain nothing from it.

## Relation to the review and the audit
Accepts R-2 (insert-only running balance) with the correction above. Accepts R-10 by dropping the claim, not
by adding a totals row. Accepts R-17 (comparator). On P-2: keeps per-statement locking for v1 with a named
optimisation, and relaxes "no database clock". ADR 0005's "database level" sentence and ADR 0008 steps 4–5
are amended by this ADR and ADR 0021.
