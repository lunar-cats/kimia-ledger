# ADR 0009: Mandatory idempotency key on every write

Status: Accepted · Date: 2026-11-13

## Context
Network retries must never create a second entry. Callers cannot know whether a timed-out request committed.

## Decision
- Every write RPC requires an `idempotency_key` (client-chosen, 1–255 chars, opaque).
- Keys are **unique per `(tenant_id, idempotency_key)`**, enforced by a DB unique constraint.
- A repeat with the **same key and same request payload** returns the **original result** (same ids, same status).
- A repeat with the **same key and a different payload** fails with `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`.
- The stored record holds a hash of the canonical request and the serialized response.
- Keys never expire in v1.

## Consequences
- Concurrent duplicates race on the unique constraint; the loser re-reads and returns the winner's result.
- Payload hashing must be canonical (field order independent) to avoid false conflicts.
- Storage for idempotency records grows with write volume; retention is a later decision.
