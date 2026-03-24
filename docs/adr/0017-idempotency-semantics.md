# ADR 0017: Idempotency semantics: one key, one outcome

Status: Proposed · Date: 2026-11-14 · Amends 0009

## Context
Review finding B-7; audit findings R-3 and R-14. ADR 0009 and I-15 do not say:
- whether all write operations share one key space;
- whether a failed request is replayed or evaluated again;
- what the payload hash covers, whether it is versioned, whether posting order counts;
- whether the client inside a tenant is part of the key;
- what "the original result" is once the resource has changed.

ADR 0009 also stores a serialised copy of the response.

## Decision
1. **Scope.** A key is unique per `(tenant_id, idempotency_key)` across all write operations. This is the rule
   already in ADR 0009, I-15 and `CLAUDE.md`. The record stores the operation. The same key on another
   operation is a conflict.
2. **Client.** The record stores the `client_id` of the token. The same key from another client of the same
   tenant is a conflict. One client cannot read another client's outcome by guessing its key.
3. **Key format.** 1 to 255 characters, printable ASCII without space (`0x21`–`0x7E`), compared byte for byte.
4. **Payload hash.** SHA-256 over a canonical encoding of: hash version, operation, and the business fields.
   - Business fields are all request fields except `idempotency_key`. Headers, the token and the correlation
     id are not included.
   - Posting order is significant: postings are hashed in the order sent, because order is stored and
     returned (ADR 0014, decision 10).
   - An absent optional field differs from a present one.
   - Encoding version 1: each field as a tag byte, a 4-byte big-endian length and its bytes; strings UTF-8;
     integers as decimal ASCII; enums by their contract number; fields in a fixed order defined per
     operation. The exact layout is fixed
     in code with golden tests before the first release (ADR 0032).
   - The record stores the hash version. A new version applies to new keys only.
5. The hash is computed in `ledger-application` from the use-case command, never from wire bytes. REST and
   gRPC therefore give the same hash.
6. **The record.** One row per key, inserted once, as the last statement of the write transaction
   (ADR 0020). Never updated, never deleted. It holds: operation, client id, hash version, hash, outcome,
   time, and the audit ids (`request_id`, `correlation_id`). The outcome is either `ACCEPTED` with the id of
   the resource, or `REJECTED` with the `ErrorCode`.
7. **What is recorded.** A rejection whose result could change if the same payload were evaluated later is
   recorded: it depends on ledger state or on the clock. These are marked "recorded" in the table of
   ADR 0018. The key is then spent: a retry returns the same rejection. To try again the caller sends a new key.
8. **What is not recorded.** Rejections that are a pure function of the payload (shape, limits, I-1 to I-4,
   I-22): a retry gives the same answer anyway. Failures that are not outcomes: authentication, scope, tenant
   status, rate limit, `CONTENTION`, `UNAVAILABLE`, `INTERNAL`. A retry with the same key is evaluated again.
9. **Replay.** Same key, same operation, same client, same hash:
   - `ACCEPTED` → the resource with the recorded id, **in its current state**, and `idempotent_replay = true`.
     An entry is immutable, so it is identical apart from the derived `reversed_by_entry_id`. An account may
     have been closed since.
   - `REJECTED` → the same `ErrorCode`.
10. Same key with another operation, client or hash → `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`
    (`ALREADY_EXISTS` / 409, ADR 0018).
11. **No serialised response is stored.** The response is rebuilt from the ledger.
12. **Concurrency.** The unique constraint is the arbiter. A duplicate that runs concurrently either waits on
    the account locks or on the key insert. If the first commits, the second hits the unique key, rolls back
    and reads the winner. Waiting is bounded by the lock and statement timeouts (ADR 0026); then the caller
    gets `CONTENTION`.
13. Keys do not expire in v1 (unchanged). Retention is an open question (threat model).

## Consequences
- One key has one outcome, for ever. A request that was rejected cannot succeed later under the same key.
- A caller that fixes the cause (for example, funds the account) must use a new key. The integration guide
  must say so.
- Cost of recording rejections (R-3): one row per state- or time-dependent rejection, never deleted in v1.
  A client that probes funds in a loop burns rows; rate limiting and the per-tenant cap (ADR 0023, 0026) bound
  it. The row holds no payload, only the hash.
- A transaction that rejects still commits its record. The transaction port returns outcomes instead of
  throwing (ADR 0030).
- The canonical encoding is a compatibility surface. It is versioned and has golden tests.
- Client credential rotation must keep the `client_id`, or in-flight retries become conflicts.

## Alternatives rejected
- **Scope `(tenant_id, operation, idempotency_key)` (review).** A key reused by mistake on another operation
  would be accepted silently. One key space reports it, needs one constraint, and lets an audit find the one
  write behind a key. It can be relaxed later; the reverse is not possible.
- **Record successes only (review, B-7).** A request rejected for `INSUFFICIENT_FUNDS` could succeed when a
  queue redelivers it after the account was funded. The caller has already acted on the rejection. (The
  earlier draft cited TigerBeetle's `id_already_failed` as precedent; not re-checked here, unverified.)
- **Store the serialised response (ADR 0009).** A second source of truth; caller data stored twice; the
  response format frozen in storage.
- **Hash the wire bytes.** Not canonical; differs between REST and gRPC.
- **Claim the key as the first statement (review, P-2).** A claim row would need its outcome written later,
  which is an UPDATE or a second row. Inserting last costs only wasted work when two duplicates really run at
  the same moment, which is rare.

## Relation to the review and the audit
Agrees on the hash and on rebuilding the response. **Differs on key scope, on failed requests and on claim
order**, for the reasons above. Accepts R-3 and R-14.
