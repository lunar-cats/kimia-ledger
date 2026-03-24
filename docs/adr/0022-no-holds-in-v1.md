# ADR 0022: No native holds in v1; holds are modelled with a hold account

Status: Proposed · Date: 2026-11-14

## Context
Review finding D-2. Card-style flows authorise an amount, then capture or void it. The contract has no
pending state.

## Decision
1. v1 has **no pending postings, no hold RPCs and no expiry timer**.
2. The pattern with plain entries, one hold account per customer and currency (LIABILITY, no overdraft):
   - authorise: debit customer, credit hold;
   - capture: debit hold, credit merchant or settlement;
   - void: debit hold, credit customer;
   - partial capture: a capture for the captured part and a void for the rest, as two entries or one entry
     with four postings.
3. Void is an ordinary entry, not `ReverseEntry`, because a reversal is all-or-nothing and only once.
4. Expiry is the caller's job. The ledger has no scheduler.
5. "Available balance" is the customer account's balance; held money has already left it.

## Consequences
- No second balance type (pending vs posted) and no timers in the write path.
- Callers create and manage hold accounts. They count against the account `external_ref` uniqueness rule
  (ADR 0025), so callers need a naming convention.
- Native holds can be added later as new RPCs without changing existing ones.

## Alternatives rejected
- **Pending postings with their own balance (as some ledgers do).** Adds state, expiry, and a second I-11
  check. No consumer has asked for it.

## Relation to the review
Agrees with D-2. Adds the void-is-not-reversal rule and the partial-capture form.
