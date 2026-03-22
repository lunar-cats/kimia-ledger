# Ledger invariants

Each invariant is numbered, stated precisely, and mapped to the error code a violation returns (`ErrorCode`
enum in `ledger.v1`, Step 5). Every invariant gets at least one property-based test. Code that enforces a rule
must cite the invariant id in a comment.

Notation: `A` account, `E` entry, `P` posting, `cur(x)` currency of x, `amt(P) > 0` amount in minor units,
`dir(P) ∈ {DEBIT, CREDIT}`.

## Structure

- **I-1 Minimum legs.** Every entry has at least 2 postings. → `INVALID_ENTRY`
- **I-2 Maximum legs.** Every entry has at most `MAX_POSTINGS_PER_ENTRY` postings (configurable, default 100). → `TOO_MANY_POSTINGS`
- **I-3 Positive amounts.** For every posting, `amt(P) ≥ 1`. Direction is carried by `dir(P)`, never by sign. → `INVALID_AMOUNT`
- **I-4 Balanced per currency.** For every entry E and every currency c appearing in E:
  `Σ amt(P) for P in E, cur(P)=c, dir(P)=DEBIT  =  Σ amt(P) for P in E, cur(P)=c, dir(P)=CREDIT`. → `UNBALANCED_ENTRY`
- **I-5 Currency match.** For every posting, `cur(P) = cur(account(P))`. An account has exactly one currency, fixed at creation. → `CURRENCY_MISMATCH`
- **I-6 Account exists in tenant.** Every posting references an account with the same `tenant_id` as the entry. A reference to another tenant's account is indistinguishable from a missing account. → `ACCOUNT_NOT_FOUND`
- **I-7 Account open.** Every posting references an account in state `OPEN`. → `ACCOUNT_CLOSED`
- **I-8 Integer money.** Amounts are integers in ISO-4217 minor units. The exponent comes from the currency table, never from the request. Arithmetic uses exact operations; overflow is an error, never a wrap. → `INVALID_AMOUNT`

## Balances

- **I-9 Balance definition.** `balance(A) = Σ amt(P)[dir=DEBIT] − Σ amt(P)[dir=CREDIT]` over all committed postings of A (signed, debit-positive). The *presented* balance is `balance(A)` for debit-normal types (ASSET, EXPENSE) and `−balance(A)` for credit-normal types (LIABILITY, EQUITY, INCOME).
- **I-10 Derived balance equals journal.** The stored balance row of A always equals I-9 recomputed from postings. A reconciliation job may verify this at any time; a mismatch is a severity-1 incident, never auto-corrected by editing postings.
- **I-11 Non-negative balance (conditional).** If `A.allow_negative_balance = false`, then after every committed entry the presented balance of A is `≥ 0`. Checked under lock, before insert. → `INSUFFICIENT_FUNDS`
  *Open decision:* default value of `allow_negative_balance` per account type (proposal: `false` for ASSET and LIABILITY, `true` for EQUITY, INCOME, EXPENSE).
- **I-12 Global zero-sum.** For every tenant and currency, Σ `balance(A)` over all accounts = 0. Follows from I-4 and I-10; tested as a property.

## Immutability and identity

- **I-13 Append-only.** No posting or entry row is ever updated or deleted. Enforced by code (no such operations exist), by DB grants (INSERT/SELECT only), and planned Immutable tables.
- **I-14 Corrections are reversals.** A correction is a new entry whose `reverses_entry_id` references an existing entry of the same tenant, with every posting mirrored in direction and equal in amount. An entry may be reversed at most once. → `ENTRY_NOT_FOUND`, `ENTRY_ALREADY_REVERSED`
- **I-15 Idempotency.** `(tenant_id, idempotency_key)` is unique. Same key + same canonical payload → the original response. Same key + different payload → `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`. Concurrent duplicates produce exactly one entry.
- **I-16 Atomicity.** Either all postings of an entry, its balance updates and its outbox row are committed, or none are.
- **I-17 Deterministic locking.** Accounts of an entry are locked in ascending `account_id` order before validation. (Prevents deadlock; no error code, a property of the implementation verified by concurrency tests.)
- **I-18 Journal order.** Each tenant has a strictly increasing `sequence_no` over committed entries. Pagination and event ordering use it. Gaps are allowed (rolled-back transactions), reordering is not.

## Tenancy and data

- **I-19 Tenant from credential.** `tenant_id` is taken from the authenticated token, never from the request body or path. Every query includes `tenant_id`.
- **I-20 No PII.** `external_ref` and `metadata` are opaque to the ledger, size-limited (255 chars / 4 KiB), and documented as must-not-contain personal data. Nothing else caller-supplied is stored.
- **I-21 Request limits.** Request size, `external_ref` length, metadata size, postings per entry (I-2) and page size are bounded before any business logic runs. → `INVALID_ARGUMENT`

## Proposed error code set (confirmed in Step 5)

`INVALID_ARGUMENT, INVALID_ENTRY, TOO_MANY_POSTINGS, INVALID_AMOUNT, UNBALANCED_ENTRY, CURRENCY_MISMATCH,
ACCOUNT_NOT_FOUND, ACCOUNT_CLOSED, ACCOUNT_ALREADY_EXISTS, INSUFFICIENT_FUNDS, ENTRY_NOT_FOUND,
ENTRY_ALREADY_REVERSED, DUPLICATE_IDEMPOTENCY_KEY_CONFLICT, PERMISSION_DENIED, UNAUTHENTICATED, RATE_LIMITED, INTERNAL`
