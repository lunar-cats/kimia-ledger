# ADR 0021: No events and no outbox in v1

Status: Proposed · Date: 2026-11-14 · Amends 0008, 0013

## Context
Review finding D-1; audit finding R-1. ADR 0008 puts an outbox insert into every write and a relay that
marks rows sent. There is no event contract, no broker choice, no relay module and no consumer.

The review says events "can be derived from the immutable journal later". That is only half true: a
tenant-wide reader over the journal needs an order that holds at commit time, which ADR 0014 shows is not
available without a tenant lock or a safe horizon.

## Decision
1. v1 has **no events, no outbox table, no relay and no broker**. ADR 0008 step 5 and its relay are removed.
   I-16 no longer mentions an outbox row.
2. v1 consumers that need changes poll `ListPostings` per account. The numbers are gapless (ADR 0014), so a
   consumer can tell it has seen everything up to a number.
3. When the first consumer needs events, one ADR adds together: the `ledger.events.v1` contract, the outbox
   insert in the write transaction, the relay, and its database user.
4. Constraint on that later design: delivery is claimed by status from the outbox (for example
   `SELECT … FOR UPDATE SKIP LOCKED`, then a sent marker), not by a cursor over a number assigned before
   commit. Events carry `account_seq` for per-account order. There is no tenant-wide order. Consumers
   de-duplicate on event id.
5. Whether the sent marker is an UPDATE by a relay user (ADR 0013) or an insert-only marker row is decided in
   that ADR.

## Consequences
- The write path is one insert shorter. No table with an UPDATE grant exists in v1.
- Adding events later is additive to `ledger.v1` (a new package for events) and adds one insert to the write
  transaction. Entries written before that have no events; a back-fill reads the journal per account.
- The threat model's broker boundary is out of scope for v1.

## Alternatives rejected
- **Keep the outbox now, publish later.** Rows accumulate with no reader and no schema; the event format would
  be frozen before a consumer exists.
- **Tenant-wide change feed by cursor over a sequence.** Skips late commits (ADR 0014).
- **Change data capture from the database.** Vendor tooling, licences and operations outside this
  repository; not evaluated.

## Relation to the review and the audit
Agrees with D-1 on cutting events from v1. **Differs** on "derive from the journal later": records why a
derived feed is not free and fixes the delivery model (accepts R-1's claim-by-status).
