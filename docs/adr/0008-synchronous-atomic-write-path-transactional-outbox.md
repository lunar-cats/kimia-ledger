# ADR 0008: Synchronous, atomic write path; events via transactional outbox

Status: Accepted · Date: 2026-11-13

## Context
Callers need an immediate, authoritative answer (posted or rejected). Downstream systems need events, but
publishing to a broker inside a DB transaction is not atomic.

## Decision
The write path for one entry is **one database transaction**:
1. Lock the involved accounts with `SELECT … FOR UPDATE` in **deterministic order (sorted by account id)**.
2. Validate: tenant, account state, currency match, balance rule, Σ debits = Σ credits per currency.
3. Insert the entry and its postings.
4. Update balance rows.
5. Insert an **outbox** row describing the event.
6. Commit. The response is returned only after commit.

A separate relay publishes outbox rows to the broker (at-least-once) and marks them sent.

## Consequences
- No deadlocks from lock-order inversion; throughput is bounded by hot-account contention, which is accepted in v1.
- Consumers must be idempotent on event id (at-least-once delivery).
- The outbox table is the only place where UPDATE is allowed (mark sent), via a dedicated relay user.
