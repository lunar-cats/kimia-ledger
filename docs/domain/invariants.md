# Ledger invariants

Each invariant is numbered, stated precisely, and mapped to the error code a violation returns (`ErrorCode`
enum in `ledger.v1`), or says why it has none. Every invariant gets at least one property-based test. Code
that enforces a rule must cite the invariant id in a comment. Ids are never reused or renumbered.

Items marked *changed* or *new* follow ADRs 0014–0032, which are **Proposed**. Until a human accepts them,
the earlier text (git history) is the accepted one. Codes marked *(new code)* are not yet in `error.proto`;
see ADR 0018.

Notation: `A` account, `E` entry, `P` posting, `cur(x)` currency of x, `amt(P) > 0` amount in minor units,
`dir(P) ∈ {DEBIT, CREDIT}`, `s(P) = +amt(P)` for DEBIT and `−amt(P)` for CREDIT.

## Structure

- **I-1 Minimum legs.** Every entry has at least 2 postings. → `INVALID_ENTRY`
- **I-2 Maximum legs.** *(changed, ADR 0016)* Every entry has at most 100 postings. 100 is a constant of
  `ledger.v1`, not configurable. → `TOO_MANY_POSTINGS`
- **I-3 Positive amounts.** *(changed, ADR 0019)* For every posting, `1 ≤ amt(P) ≤ 2^63−1`. Direction is carried by `dir(P)`,
  never by sign. Also a row `CHECK` in the database (ADR 0019). → `INVALID_AMOUNT`
- **I-4 Balanced per currency.** *(changed, ADR 0020: database claim dropped)* For every entry E and every currency c appearing in E:
  `Σ amt(P) for P in E, cur(P)=c, dir(P)=DEBIT  =  Σ amt(P) for P in E, cur(P)=c, dir(P)=CREDIT`. Enforced in
  `ledger-core`, not by the database (ADR 0020). → `UNBALANCED_ENTRY`
- **I-5 Currency match.** For every posting, `cur(P) = cur(account(P))`. An account has exactly one currency,
  fixed at creation. → `CURRENCY_MISMATCH`
- **I-6 Account exists in tenant.** *(changed, ADR 0027)* Every posting references an account with the same `tenant_id` as the
  entry. A reference to another tenant's account is indistinguishable from a missing account. Backed by
  composite tenant foreign keys (ADR 0027). → `ACCOUNT_NOT_FOUND`
- **I-7 Account open.** *(changed, ADR 0025)* Every posting references an account that is not `CLOSED`.
  → `ACCOUNT_CLOSED`. (A frozen account: I-28.)
- **I-8 Integer money.** *(changed, ADR 0019)* Amounts are integers in minor units. The exponent is copied
  from the currency table when the account is created and never changes. Arithmetic uses exact operations;
  overflow is an error, never a wrap. A per-entry sum outside `int64` → `INVALID_AMOUNT`; a balance outside
  `int64`: I-26.

## Balances

- **I-9 Balance definition.** `balance(A) = Σ amt(P)[dir=DEBIT] − Σ amt(P)[dir=CREDIT]` over all committed
  postings of A (signed, debit-positive). The *presented* balance is `balance(A)` for debit-normal types
  (ASSET, EXPENSE) and `−balance(A)` for credit-normal types (LIABILITY, EQUITY, INCOME).
- **I-10 Stored balance equals journal.** *(changed, ADR 0020, 0029)* For an account with postings
  `P1..Pn` in `account_seq` order: `balance_after(P1) = s(P1)` and `balance_after(Pk) = balance_after(Pk−1) +
  s(Pk)`; hence `balance_after(Pn) = balance(A)`. There is no separate balance table. Reconciliation verifies
  the chain; a mismatch is a severity-1 incident, the account is frozen (I-28), and nothing is rebuilt or
  edited. No error code: a request cannot violate it; a bug or tampering can, and reconciliation finds it.
- **I-11 Non-negative balance (conditional).** *(changed, ADR 0016, 0025)* `allow_negative_balance` is
  required at creation and immutable; there is no default. If it is `false`, then after every committed entry
  the presented balance of A is `≥ 0`: checked on the `balance_after` of A's last posting in the entry,
  under lock, before insert. An intermediate `balance_after` inside one entry may be lower. Reversals get no
  exemption. → `INSUFFICIENT_FUNDS`
- **I-12 Global zero-sum.** For every tenant and currency, Σ `balance(A)` over all accounts = 0. Follows from
  I-4 and I-10; tested as a property.

## Immutability and identity

- **I-13 Append-only.** *(changed, ADR 0020)* No row of accounts, entries, postings, reversal links, account
  closures or idempotency records is ever updated or deleted. The runtime database user has `INSERT` and
  `SELECT` only. Enforced by code (no such operations exist), by grants, and planned immutable tables
  (ADR 0028). No error code: no operation exists that could violate it.
- **I-14 Corrections are reversals.** *(changed, ADR 0016)* A reversal is built by the server from an existing
  entry of the same tenant (`ReverseEntry`): same accounts, amounts and order, every direction flipped. Each
  entry is reversed at most once, backed by a key on `(tenant_id, original_entry_id)`. A reversal cannot be
  reversed. A reversal is validated like any entry. → `ENTRY_NOT_FOUND`, `ENTRY_ALREADY_REVERSED`,
  `ENTRY_NOT_REVERSIBLE` *(new code)*
- **I-15 Idempotency.** *(changed, ADR 0017)* `(tenant_id, idempotency_key)` is unique across all write
  operations. Same key + same operation + same client + same canonical payload → the recorded outcome
  (the resource in its current state, or the recorded rejection). Any difference →
  `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`. Rejections that depend on state or time are recorded; the table is in
  ADR 0018. Concurrent duplicates produce exactly one outcome.
- **I-16 Atomicity.** *(changed, ADR 0021)* Either the entry, all its postings, its reversal link (if any)
  and its idempotency record are committed, or none are. There is no outbox in v1. No error code: a property
  of the transaction, tested by fault injection.
- **I-17 Deterministic locking.** *(changed, ADR 0020)* The distinct accounts of an entry are locked before
  validation in ascending unsigned byte order of their 16-byte UUID (equal to ascending order of the
  canonical lowercase string). Not `UUID.compareTo`. A lock wait timeout or a detected deadlock →
  `CONTENTION` *(new code)*. Lock order prevents deadlock; it is not needed for correctness.
- **I-18 Per-account posting order.** *(changed, ADR 0014; the tenant-wide `sequence_no` is withdrawn)* The
  committed postings of each account carry `account_seq` exactly `1..n`, without gaps or duplicates, in
  commit order. Unique on `(tenant_id, account_id, account_seq)`. There is no tenant-wide order. A violation
  is a server bug → `INTERNAL`.

## Tenancy and data

- **I-19 Tenant from credential.** `tenant_id` is taken from the authenticated token, never from the request
  body or path. Every query includes `tenant_id`.
- **I-20 No PII.** *(changed, ADR 0025)* `external_ref` is opaque to the ledger, 1–255 characters on
  accounts, 0–255 on entries, and documented as must-not-contain personal data. There is no `metadata`.
  Nothing else caller-supplied is stored as text. No error code: content cannot be checked; length is I-21.
- **I-21 Request limits.** *(changed, ADR 0016, 0025)* Request size, `external_ref` length, postings per entry
  (I-2) and page size are bounded before any business logic runs. The limits are constants of `ledger.v1`.
  → `INVALID_ARGUMENT`

## Added by ADRs 0016–0025, 0029 *(new)*

- **I-22 Distinct accounts.** Every entry touches at least two distinct accounts. → `INVALID_ENTRY`
- **I-23 Immutable account attributes.** `external_ref`, type, currency, exponent and
  `allow_negative_balance` never change after creation. No error code: no operation exists that changes them
  (I-13).
- **I-24 Closing.** An account can be closed only when its presented balance is 0, checked under the account
  lock. `CLOSED` is terminal, so a closed account has balance 0 for ever. → `ACCOUNT_BALANCE_NOT_ZERO`
  *(new code)*; closing a closed account → `ACCOUNT_CLOSED`.
- **I-25 Unique account reference.** `(tenant_id, external_ref)` is unique over accounts, compared byte for
  byte. → `ACCOUNT_ALREADY_EXISTS` *(new code)*
- **I-26 Balance range.** Every `balance_after` is within `−2^63 .. 2^63−1`. → `BALANCE_LIMIT_EXCEEDED`
  *(new code)*
- **I-27 Effective time bounds.** `effective_at ≤ posted_at + 5 min` and `effective_at ≥ posted_at −` the
  deployment's back-dating limit. → `EFFECTIVE_TIME_OUT_OF_RANGE` *(new code)*
- **I-28 Frozen account.** An account with a freeze row takes no postings and cannot be closed. Only an
  operator freezes or unfreezes. → `ACCOUNT_FROZEN` *(new code)*
- **I-29 Registered tenant.** A request is served only if `(issuer, tenant claim)` maps to a tenant with status
  `ACTIVE`. → `PERMISSION_DENIED`
- **I-30 Overdraft needs privilege.** Creating an account with `allow_negative_balance = true` needs scope
  `accounts:overdraft`. → `PERMISSION_DENIED`

## Error codes

Defined once, in `contracts/proto/ledger/v1/error.proto` (`ledger.v1.ErrorCode`), with the gRPC and HTTP status
each is delivered with. Values are only ever appended. The full mapping, including which rejections are
recorded under the idempotency key, is in ADR 0018. Account `external_ref` is unique (I-25), so
`ACCOUNT_ALREADY_EXISTS` exists.
