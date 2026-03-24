# ADR 0029: Reconciliation, account freeze, recovery and retention

Status: Proposed · Date: 2026-11-14 · Amends 0007

## Context
Audit finding R-7; review D-14. ADR 0007 says balances "may be rebuilt from the journal at any time". The
threat model lists "balance row changed without postings" as a threat. A rebuild that silently overwrites a
balance is that threat. No document states a recovery point objective, a disaster-recovery plan, archival or
partitioning, or what happens to an account after an I-10 mismatch.

## Decision
1. **Nothing is rebuilt.** With ADR 0020 there is no derived balance table; every stored value is in an
   insert-only row. ADR 0007's rebuild sentence is withdrawn. Read models added later may be rebuilt; the
   journal never is.
2. **Reconciliation** checks, per tenant:
   - per account: `account_seq` is `1..n` without gaps, and the `balance_after` chain holds (I-10);
   - per entry: I-4;
   - per currency: Σ of latest balances is 0 (I-12);
   - every `ACCEPTED` idempotency record points to an existing entry or account.
   Frequency to be set. It runs as a read-only database user.
3. **Freeze.** On a mismatch the operator freezes the affected open accounts: a row in a freeze table that only
   an operator database user can insert or delete. The runtime user reads it under the account lock. A frozen
   account rejects postings and closing → `ACCOUNT_FROZEN` (I-28); reads still work. The API cannot freeze or
   unfreeze in v1.
4. **A mismatch is a severity-1 incident.** Journal rows are never edited. The correction, once the cause is
   known, is new entries posted by a human-approved procedure (runbook: follow-up).
5. **Recovery point and time objectives are a human decision.** The ledger acknowledges a write only after
   commit. If the recovery point is above zero, entries acknowledged in the lost window disappear; callers
   must then resubmit them, and the same idempotency keys make that safe. The runbook must say so.
6. **Retention.** No row is deleted in v1, including idempotency records. Archival and partitioning are
   decided when volume is known; partitioning may need a separately licensed Oracle option (unverified).

## Consequences
- The threat "balance changed without postings" no longer has a mutable target.
- Freezing needs an operator database user and a runbook before the first production deployment.
- Storage grows without bound in v1; the size per entry is to be measured.

## Alternatives rejected
- **Automatic rebuild on mismatch.** Hides the evidence and may overwrite a correct value with a wrong one.
- **Freeze through an API scope.** Puts an incident control into every client credential model; deferred.
- **Freeze as a status column.** Needs an UPDATE grant for the runtime user (ADR 0020).

## Relation to the audit
Accepts R-7: rebuild withdrawn, freeze state added, recovery objectives assigned to the human.
