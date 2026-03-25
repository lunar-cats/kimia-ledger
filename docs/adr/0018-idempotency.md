# ADR 0018: Idempotency: one key, one outcome

Status: Proposed · Date: 2026-11-15 · Amends 0009

Replaces earlier drafts: old 0017 (first written 2026-11-14; revised here for PostgreSQL and the operations of
2026-11-15).

## Context
Review finding B-7; audit findings R-3 and R-14; review §11.2, §19.1. ADR 0009 and I-15 did not say:
- whether all write operations share one key space;
- whether a failed request is replayed or evaluated again;
- what the payload hash covers, whether it is versioned, whether posting order counts;
- whether the client inside a tenant is part of the key;
- what "the original result" is once the resource has changed.

ADR 0009 also stored a serialised copy of the response. The review (§19.1) kept the semantics and asked for:
PostgreSQL transaction abort handling, an advisory lock, `REJECTED` records kept, and the wallet, bucket, hold,
FX, settlement and fence operations in the same key space.

## Decision

### D1. Scope, client and key format
1. A key is unique per `(tenant_id, idempotency_key)` across **all** write operations, including wallet, bucket,
   hold, FX, settlement and fence operations (I-15). The record stores the operation. The same key on another
   operation is a conflict.
2. The record stores the `client_id` of the token. The same key from another client of the same tenant is a
   conflict. One client cannot read another client's outcome by guessing its key.
3. Format: 1 to 255 characters, printable ASCII without space (`0x21`–`0x7E`), compared byte for byte.
4. Workers and the migration build deterministic keys: `system:expire:{hold_id}` (ADR 0023 D3),
   `sarv:{OperationType}:{ReferenceId}` (review §11.2).

### D2. Payload hash
1. SHA-256 over a canonical encoding of: hash version, operation, and the business fields.
   - Business fields are all request fields except `idempotency_key`. Headers, the token and the correlation
     id are not included.
   - Posting order is significant: postings are hashed in the order sent, because order is stored and
     returned (ADR 0016 D1.5).
   - An absent optional field differs from a present one.
   - Encoding version 1: each field as a tag byte, a 4-byte big-endian length and its bytes; strings UTF-8;
     integers (amounts included, ADR 0015) as decimal ASCII; enums by their contract number; fields in a fixed
     order defined per operation. The exact layout is fixed in code with golden tests before the first release
     (ADR 0019 D3).
   - The record stores the hash version. A new version applies to new keys only.
2. The hash is computed in `ledger-application` from the use-case command, never from wire bytes. REST and
   gRPC therefore give the same hash.

### D3. The record, and what is recorded
1. One row per key in `idempotency_records`, inserted once, as the last statement of the write transaction
   (ADR 0017 D5). Never updated, never deleted. It holds: operation, client id, hash version, hash, outcome,
   time, and the audit ids (`request_id`, `correlation_id`). The outcome is either `ACCEPTED` with the id of
   the resource, or `REJECTED` with the `ErrorCode`.
2. **Recorded:** a rejection whose result could change if the same payload were evaluated later, because it
   depends on ledger state or on the clock (marked "recorded" in ADR 0019 D2). The key is then spent: a retry
   returns the same rejection. To try again the caller sends a new key.
3. **Not recorded:** rejections that are a pure function of the payload (shape, limits, I-1 to I-4, I-22): a
   retry gives the same answer anyway. Failures that are not outcomes: authentication, scope, tenant status,
   rate limit, `CONTENTION`, `UNAVAILABLE`, `INTERNAL`. A retry with the same key is evaluated again.
4. **No serialised response is stored.** The response is rebuilt from the ledger.

### D4. Replay and conflict
1. Same key, same operation, same client, same hash:
   - `ACCEPTED` → the resource with the recorded id, **in its current state**, and `idempotent_replay = true`.
     A transaction is immutable, so it is identical apart from the derived `reversed_by_transaction_id`. A
     wallet, bucket or hold may have changed state since.
   - `REJECTED` → the same `ErrorCode`.
2. Same key with another operation, client or hash → `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`
   (`ALREADY_EXISTS` / 409, ADR 0019 D2).

### D5. Concurrency and retention
1. Inside the write transaction, `pg_advisory_xact_lock(key64)` on a 64-bit hash of `(tenant_id,
   idempotency_key)` serialises duplicates before the lookup (ADR 0017 D5 step 7). A duplicate that runs
   concurrently waits on the advisory lock; when the first commits, the second finds the record and replays or
   conflicts. A 64-bit hash collision only causes an unneeded wait, never a wrong outcome. The unique
   constraint stays the arbiter: a unique violation rolls back (PostgreSQL aborts the whole
   transaction) and the winner is read in a new read. Waiting is bounded by the timeouts of ADR 0017 D7; then
   the caller gets `CONTENTION`.
2. Keys do not expire in v1. `ACCEPTED` records live as long as the journal; `REJECTED` records are not
   deleted until legal retention is decided, and any retention must exceed the client retry horizon plus the
   time to resolve an ambiguity (review §11.2).

## Consequences
- One key has one outcome, for ever. A request that was rejected cannot succeed later under the same key.
- A caller that fixes the cause (for example, funds the bucket) must use a new key. The integration guide
  must say so.
- Cost of recording rejections (R-3): one row per state- or time-dependent rejection, never deleted in v1.
  A client that probes funds in a loop burns rows; rate limiting and the per-tenant cap (ADR 0020 D3,
  ADR 0017 D7) bound it. The row holds no payload, only the hash.
- A transaction that rejects still commits its record. The transaction port returns outcomes instead of
  throwing (ADR 0026 D1).
- The canonical encoding is a compatibility surface. It is versioned and has golden tests.
- Client credential rotation must keep the `client_id`, or in-flight retries become conflicts.

## Open items
- Retention of `REJECTED` idempotency records (owner: legal/DPO; assumption: never deleted until decided;
  needs: legal retention periods, review §20.1).

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
  which is an UPDATE or a second row. The advisory lock gives the serialisation without a claim row.
