# ADR 0019: Money range, storage width, currency column and exponent

Status: Proposed · Date: 2026-11-14 · Amends 0002, 0006

## Context
Review finding B-9; audit findings R-9, R-20.
- The wire carries `int64`. ADR 0002 and ADR 0006 store money as `NUMBER(38,0)`. A value larger than `int64`
  could be stored but never returned.
- The review proposes `int64` everywhere. The audit says this is wrong because a balance (a sum) can exceed
  `int64` while each posting cannot, and proposes `NUMBER(38,0)` balances returned as a decimal string.
- Currency is `CHAR(3)`. The exponent is looked up from ISO-4217 on every use. If ISO changes a currency's
  exponent, every stored amount of that currency silently changes meaning.

## Decision
1. **One range.** Posting amounts are `1 .. 2^63−1`. Every `balance_after` (ADR 0020) and every per-entry
   per-currency sum is within `−2^63 .. 2^63−1`. Java `long` with exact arithmetic (I-8).
2. A per-entry sum that overflows → `INVALID_AMOUNT` (a function of the payload). A write that would take a
   balance outside the range → `BALANCE_LIMIT_EXCEEDED` (depends on state, recorded; I-26).
3. **Columns.** Amounts and balances are `NUMBER(19,0)` with a row-level `CHECK` for the exact `int64` range;
   amounts also `CHECK (amount >= 1)`. Oracle `NUMBER` is variable-length, so the declared precision costs no
   space (unverified, human to confirm; it does not change the decision).
4. **Currency column** `VARCHAR2(12 BYTE)`, with a foreign key to a currency table `(code, exponent)`. v1
   seeds that table with ISO-4217 codes by migration and the API accepts only those. A non-ISO unit later is
   a row in that table plus a contract change, not a column change on immutable tables (ADR 0028).
5. **Exponent per account.** At creation the account copies the exponent from the currency table. It never
   changes (I-23). `Account` returns it as output-only `currency_exponent`. If ISO-4217 changes an exponent,
   only accounts created afterwards use the new one.
6. **Rounding is the caller's job.** The ledger only adds integers.
7. **Foreign exchange** is four postings with one position account per currency. Example: a customer pays
   EUR 100.00 and receives USD 108.50.

| Account | Direction | Money |
|---|---|---|
| customer EUR | DEBIT | 10000 EUR |
| FX position EUR | CREDIT | 10000 EUR |
| FX position USD | DEBIT | 10850 USD |
| customer USD | CREDIT | 10850 USD |

   Each currency balances on its own (I-4). The rate and its rounding live in the caller. Position accounts
   go negative, so they need the overdraft scope (ADR 0024).

## Consequences
- An account that reaches `2^63−1` minor units cannot take further increases. At exponent 2 that is about
  9.2 × 10^16 major units. The caller must split such an account. This is a defined, recorded rejection, not
  a silent wrap.
- Wire, Java and database agree on one range; no decimal-string money type exists in v1.
- `CHECK` constraints are real database enforcement, but per row only (see ADR 0020 on what the database does
  not enforce).
- Proto follow-up: `Account.currency_exponent`; codes in ADR 0018.

## Alternatives rejected
- **`NUMBER(38,0)` balances as a decimal string (audit R-9).** Two money types on the wire, big-integer
  parsing in every client, and a balance that no posting amount can ever move back in one step. The extra
  range buys nothing realistic for fiat currencies in v1. Can be added later as a new field if a real case
  appears.
- **`CHAR(3)` (current).** Excludes every non-ISO unit, and a column change on an immutable table may not be
  possible.
- **Exponent looked up at read time.** An ISO change would rescale history.
- **Server-side FX or rounding.** Needs rates, which the ledger does not own.

## Relation to the review and the audit
Agrees with B-9 on `int64` and the 12-character column. **Rejects R-9** for the reasons above, but takes its
point: balance overflow gets its own recorded code. Accepts R-20 (exponent per account, FX pattern, rounding).
