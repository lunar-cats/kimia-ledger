# ADR 0026: Timeouts, per-tenant concurrency cap and hot accounts

Status: Proposed · Date: 2026-11-14

## Context
Audit finding R-4. No document states a lock-wait, statement or transaction timeout, a per-tenant limit on
concurrent writes, or a capacity target. ADR 0008 accepts hot-account contention without saying what it
costs: if every entry of a tenant touches one fee account, that tenant's writes run one at a time.

## Decision
1. **Every write has a deadline.** The transaction deadline is the smaller of the server maximum and the
   caller's gRPC deadline minus a margin. REST requests use the server maximum.
2. **Three bounds**, all mapped to `CONTENTION` when hit inside the write transaction:

| Bound | Mechanism | Value |
|---|---|---|
| Lock wait per account | `SELECT … FOR UPDATE WAIT n` (whole seconds on Oracle; unverified) | to be measured |
| Statement timeout | JDBC query timeout, also bounds the idempotency-key insert wait | to be measured |
| Transaction deadline | set by the transaction port (ADR 0030) | to be measured |

3. **Per-tenant cap** on in-flight write transactions per instance, and so on the connections one tenant can
   hold. Over the cap → `RATE_LIMITED` without touching the database. Value to be measured.
4. **Hot accounts are serialised** in v1. The documented caller-side mitigation: split a hot account (for
   example a fee account) into N sub-accounts, choose one per entry, sum them for reporting.
5. **No capacity promise** until a load test on the target Oracle setup measures: entries per second per
   tenant, entries per second on one hot account, p99 write latency. The human sets the targets.
6. Clients retry `CONTENTION` with the same key, exponential backoff and jitter.

## Consequences
- A slow or hot tenant cannot hold every pool connection.
- Every number above stays open until measured; the code reads them from configuration with no default in
  production profiles.
- Lock wait in whole seconds is coarse; if it is too coarse, the statement timeout is the finer bound.

## Alternatives rejected
- **Invent numbers now.** They would be copied into code and never revisited.
- **Server-side hot-account relief (batching, sharded balances) in v1.** Changes the balance model; not
  justified before measurement.
- **`NOWAIT` locks.** Fails every contended write at once; turns a short wait into client retries.

## Relation to the audit
Accepts R-4: policy decided, numbers marked "to be measured".
