# ADR 0025: Account lifecycle, lookup and caller-supplied fields

Status: Proposed · Date: 2026-11-14 · Amends 0011

## Context
Review findings D-3, D-4, D-5, D-11, D-12, S-6, S-7. Checked against the draft contract:
- `external_ref` is not unique and cannot be searched. A retry with a new idempotency key creates a second
  account, and every consumer keeps its own mapping table.
- `CLOSED` and `ACCOUNT_CLOSED` exist; no RPC closes an account.
- There is no list of accounts or entries.
- `CreateAccountRequest.allow_negative_balance` is `optional` with a type-derived default in the proto
  comment, while I-11 calls the default an open decision.
- `metadata` appears in I-20, ADR 0011, the checklist and the integration guide, and in no proto.
- Immutability of type and currency is a proto comment, not an invariant.

## Decision
1. **Unique `external_ref` for accounts**, per tenant, compared byte for byte (I-25). A create with a used
   value → `ACCOUNT_ALREADY_EXISTS` (recorded). The error does not include the existing account id. Entry
   `external_ref` stays non-unique.
2. **`ListAccounts`** (`GET /v1/accounts`, scope `accounts:read`), paginated by account id in the I-17
   order, with an optional exact-match filter `external_ref`. It is not a snapshot: an account created during
   the listing may or may not appear. Nothing is ever repeated, because accounts are never deleted.
3. **`ListEntries` is not offered in v1** (ADR 0014). Entries are reached by id or through `ListPostings`.
4. **`CloseAccount`** (`POST /v1/accounts/{account_id}/close`, scope `accounts:write`, idempotency key
   required). Under the account lock: presented balance must be 0 → else `ACCOUNT_BALANCE_NOT_ZERO`; already
   closed → `ACCOUNT_CLOSED`; frozen → `ACCOUNT_FROZEN`. Closing inserts a closure row with primary key
   `(tenant_id, account_id)` (ADR 0020). `CLOSED` is terminal (I-24).
5. **`allow_negative_balance` is required** on create (absent → `INVALID_ARGUMENT`). No default by type.
   `true` needs the overdraft scope (ADR 0024).
6. **Immutable after creation** (I-23): `external_ref`, type, currency, exponent, `allow_negative_balance`.
   v1 has no RPC that changes them.
7. **`AccountStatus`** gains `FROZEN` (ADR 0029). Status is derived on read: `FROZEN` if a freeze row exists,
   else `CLOSED` if a closure row exists, else `OPEN`. Only open accounts can be frozen.
8. **No `metadata`** in v1, on any message. The only caller-supplied free text is `external_ref`
   (1–255 characters on accounts, 0–255 on entries). It is removed from I-20, I-21, ADR 0011's decision,
   the checklist and the integration guide.

## Consequences
- A caller retrying `CreateAccount` with a lost key gets `ACCOUNT_ALREADY_EXISTS` and can look the account up.
- An `external_ref` can never be reused, even after closing. Callers that erase a customer's link (ADR 0011)
  must not recycle the value.
- Closing cannot race a posting, because both take the same account lock.
- Proto follow-up: `ListAccounts`, `CloseAccount`, `ACCOUNT_STATUS_FROZEN`, required
  `allow_negative_balance`, `currency_exponent`, `external_ref` comment.

## Alternatives rejected
- **Separate `LookupAccount` RPC.** A filter on `ListAccounts` covers it with one RPC.
- **Reopen a closed account.** Breaks "closed means zero, for ever" and the terminal state.
- **Close with a non-zero balance and sweep automatically.** The ledger would choose a counter-account.
- **Status column updated in place.** Needs an UPDATE grant (ADR 0020).
- **Keep `metadata` with a size limit.** The threat model names it the most likely PII leak into an
  immutable store. It can be added later; it can never be removed from stored rows.

## Relation to the review
Agrees with D-3, D-4, D-11, D-12, S-6, S-7. **Differs on D-5**: `ListEntries` is deferred (ADR 0014).
D-13 is decided in ADR 0016.
