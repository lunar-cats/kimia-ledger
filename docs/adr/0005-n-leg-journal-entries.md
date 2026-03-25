# ADR 0005: N-leg journal entries with per-currency balance, five account types

Status: Accepted · Date: 2026-11-13

Amended by ADR 0016, ADR 0017, ADR 0021 (Proposed). The text below is unchanged; where it conflicts with an accepted amendment, the amendment wins. Ledger-owned account lifecycle statements are historical (ADR 0021).

## Context
Real transactions touch more than two accounts (fees, taxes, splits). Two-leg "transfer" models force callers
to decompose and lose atomicity.

## Decision
- A **journal entry** is the unit of write. It contains **≥ 2 postings** and is committed atomically or not at all.
- Each posting references one account, one direction (`DEBIT`/`CREDIT`), one amount (> 0) and one currency.
- Invariant: for every currency in an entry, **Σ debits = Σ credits**. Multi-currency entries are allowed only
  if each currency balances on its own.
- Account types: `ASSET`, `LIABILITY`, `EQUITY`, `INCOME`, `EXPENSE`. Each has a normal balance side
  (debit for ASSET/EXPENSE, credit for the others). Each account has exactly one currency.
- The precise statements live in `docs/domain/invariants.md`.

## Consequences
- The ledger enforces double-entry at the type and database level; a one-sided posting cannot exist.
- Balance presentation respects the normal side; storage uses signed debit-normal arithmetic.
- Property-based tests generate arbitrary balanced and unbalanced entries to exercise the invariant.
