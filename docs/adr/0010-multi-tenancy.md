# ADR 0010: Multi-tenancy by tenant_id from day one

Status: Accepted · Date: 2026-11-13

Amended by ADR 0023, ADR 0027 (Proposed). The text below is unchanged; where it conflicts with an accepted amendment, the amendment wins.

## Context
One deployment serves several isolated tenants. Retrofitting tenancy into a ledger is high risk.

## Decision
- Every table carries `tenant_id`; every primary and unique key is prefixed with it.
- The tenant is derived from the **authenticated client credential** (JWT claim), never from the request body or URL.
- Every query, including reads and idempotency look-ups, filters by `tenant_id`. Cross-tenant references
  (an entry touching another tenant's account) are rejected as `ACCOUNT_NOT_FOUND`, not as a distinct error, to avoid leaking existence.
- Tenant isolation is a test category of its own (see test-engineer agent).

## Consequences
- No shared accounts across tenants; inter-tenant movements are two entries in two ledgers, settled externally.
- Row-level security or per-tenant schemas remain possible later without API change.
