# ADR 0024: A separate scope to create accounts that may go negative

Status: Proposed · Date: 2026-11-14 · Amends 0013

## Context
Audit finding R-5. Authorization stops at tenant and RPC scope. A client with `accounts:write` and
`entries:write` can create an account with `allow_negative_balance = true` and post from it to any account of
the tenant: it can create money inside the tenant. An overdraft account is the only source of value that
needs no prior funding.

## Decision
1. Creating an account with `allow_negative_balance = true` needs the scope **`accounts:overdraft`** in
   addition to `accounts:write`. Missing → `PERMISSION_DENIED` (I-30).
2. `allow_negative_balance` is required on create and immutable (ADR 0025), so the scope is checked once.
3. Posting is not restricted per account in v1. Any client with `entries:write` may post to and from any
   account of its tenant, including overdraft accounts. This is a stated, accepted risk in the threat model.

## Consequences
- Operators can give most integrating services `accounts:write` without the power to create money sources.
- The scope gates creation, not use. A client with `entries:write` can still drain an existing overdraft
  account into any account. Per-account posting rights are an open question for the human.
- `CLAUDE.md`, ADR 0013 and the proto comments list one more scope (follow-up).

## Alternatives rejected
- **Per-account ACLs in v1.** A permission model, its storage and its API; no consumer has asked for it.
- **Type-based defaults (EQUITY, INCOME, EXPENSE may go negative).** Makes creation of money sources implicit
  in the account type (ADR 0025).
- **A separate "system account" concept.** Same effect as the flag with more vocabulary.

## Relation to the audit
Accepts R-5 for creation; records the residual risk for posting.
