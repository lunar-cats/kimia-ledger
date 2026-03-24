# ADR 0027: Tenant isolation in the database: composite keys now, row policies later

Status: Proposed · Date: 2026-11-14 · Amends 0010

## Context
Audit finding R-11. Tenant isolation rests on every query carrying `tenant_id = ?` (ADR 0010, I-19). One
missing predicate leaks or corrupts across tenants. ADR 0010 already prefixes every primary and unique key with
`tenant_id`, but says nothing about foreign keys.

## Decision
1. **Every foreign key includes `tenant_id`**: posting → account, posting → entry, reversal link → both
   entries, closure → account, idempotency record → tenant. Referenced keys are `(tenant_id, id)`. The
   database then refuses a row that references another tenant's row, whatever the query did.
2. Every persistence port method takes the tenant as its first parameter; an ArchUnit test checks the
   signatures.
3. **Row-level policies (Oracle VPD) are deferred.** Whether the target Oracle edition includes VPD is
   unverified; human to confirm. When available, a policy keyed on a session tenant context is a second
   layer, not a replacement for the predicate.
4. The tenant-isolation test suite (ADR 0010) runs every query with two tenants and asserts no cross-read.

## Consequences
- Wrong-tenant writes fail at the database with a constraint error (mapped to `INTERNAL`; it is a bug).
- Wrong-tenant reads are still possible if a predicate is missing; tests and review cover them until a row
  policy exists.
- Composite keys are slightly wider indexes.

## Alternatives rejected
- **Schema per tenant.** Migrations multiply by tenants; connection handling changes.
- **VPD now.** Edition and licence unverified; session-context handling adds a failure mode to every
  connection checkout.

## Relation to the audit
Accepts R-11 for composite keys; defers row policies with the reason.
