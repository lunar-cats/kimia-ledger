# ADR 0020: Identity, tenancy, authorization scopes, edge controls and tenant isolation in the database

Status: Proposed · Date: 2026-11-15 · Amends 0010, 0013

Replaces earlier drafts: old 0023, old 0024, old 0027 (all first written 2026-11-14; revised here for PostgreSQL
and the ownership boundary of 2026-11-15).

## Context
Review findings D-9, D-10; audit findings R-5, R-11; review §13.4, §15, §19.1. ADR 0010 says the tenant is "a JWT
claim" without naming it. Nothing said which issuers and audiences are trusted, how a tenant comes to exist or is
suspended, where TLS terminates, whether a token is bound to the client certificate, or where rate limits apply.
ADR 0013 promises "fail closed" without saying when. Authorization stopped at tenant and RPC scope: a client
able to create an account that may go negative could create money inside the tenant (R-5). Tenant isolation
rested on every query carrying `tenant_id = ?` (ADR 0010, I-19); one missing predicate leaks or corrupts across
tenants, and ADR 0010 said nothing about foreign keys (R-11).

## Decision

### D1. Tokens and the tenant registry (old 0023)
1. The service trusts an **allow-list of issuers**, set in deployment configuration. Per issuer: issuer URL,
   JWKS location, accepted audiences, accepted signature algorithms (asymmetric only; `none` and HMAC are
   refused), and the **tenant claim name** (default `tenant_id`).
2. Checked on every request: signature, `iss`, `aud`, `exp`, `nbf` with at most 60 s skew, presence of the
   tenant claim, and scope (`scope` claim, space-separated). `client_id` is read for audit and idempotency
   (ADR 0018 D1); the claim name follows RFC 9068 (unverified, human to confirm against the chosen issuer).
3. **Tenant registry.** A table maps `(issuer, tenant claim value)` to an internal `tenant_id` with status
   `ACTIVE` or `SUSPENDED`. Tenants are created and suspended by an operator procedure (`ledger_operator`,
   ADR 0014 D3), not by the API. Unknown or suspended → `PERMISSION_DENIED` (I-29). The service may cache the
   registry for at most a configured time (value to be set; it is the suspension delay).
4. **Certificate-bound tokens** (RFC 8705 `cnf` / `x5t#S256`, unverified here): required by default. A
   deployment may switch the check off per issuer; the service then logs a warning at start-up.

### D2. TLS termination and fail closed (old 0023)
1. **mTLS terminates in the service process** in v1. The service needs its certificate, key and the client CA
   bundle at start-up. No proxy or sidecar terminates TLS in a supported v1 deployment.
2. Management endpoints (health) use a separate port without ledger data (review S-1).
3. The service refuses to start without: TLS material, client CA, at least one issuer with an audience, and a
   reachable tenant registry. At run time, if JWKS or the registry cannot be read and the cache is stale,
   requests fail with `UNAVAILABLE`. There is no fallback that skips a check.

### D3. Rate limiting (old 0023)
1. **In the service**, per `(tenant_id, client_id)`, token bucket, in memory per instance. Limits come from
   configuration, per tenant where set. The effective cluster limit is per-instance limit × instances; the
   operator sizes for that.
2. Rate limiting runs after authentication and before idempotency. A limited request is not recorded
   (ADR 0018 D3) and returns `RATE_LIMITED` with `RetryInfo`. A gateway may add its own limits; the service does
   not rely on one. The per-tenant concurrency cap is ADR 0017 D7.

### D4. Tenant isolation in the database (old 0027)
1. **Every foreign key includes `tenant_id`**; referenced keys are `(tenant_id, id)`; `ON DELETE RESTRICT`, never
   cascade (ADR 0014 D3). The database refuses a row that references another tenant's row, whatever the query
   did. A wrong-tenant write fails with a constraint error, mapped to `INTERNAL` (a bug).
2. Every persistence port method takes the tenant as its first parameter; an ArchUnit test checks the signatures.
3. **Row-level security is the second layer** (ADR 0014 D4), not a replacement for the explicit predicate. The
   first draft deferred row policies because the Oracle edition was unverified; on PostgreSQL there is no edition
   limit. Its cost is a benchmark item.
4. The tenant-isolation test suite (ADR 0010) runs every query with two tenants and asserts no cross-read.

### D5. Authorization scopes and money sources (old 0024)
1. Scopes: `wallets:read`, `wallets:write`, `wallets:freeze`, `transactions:read`, `transactions:write`,
   `transactions:system`, `transactions:exceptional`, `holds:read`, `holds:write`, `balances:read`,
   `postings:read`, `fx:write`, `settlement:write`, `reconciliation:*`, and `accounts:fence` (Account Service
   identity only, ADR 0022 D5). Origin scopes and approval rules are ADR 0021 D5.
2. **Creating a money source is a privileged act.** The first draft gated accounts that may go negative behind a
   separate scope `accounts:overdraft` (I-30 as first written). Ledger no longer creates accounts (ADR 0021 D1):
   the privilege moves to the Account Service, and Ledger enforces only the versioned overdraft policy it receives
   (ADR 0022 D8). No request field sets or raises a limit. Buckets do not go negative by default.
3. **Posting is not restricted per wallet, account or class in v1.** Any client with `transactions:write` may post
   to and from any target of its tenant. This is a stated, accepted residual risk in the threat model.

## Consequences
- An operator procedure (and its database role) for the registry is needed before the first tenant exists. The
  runtime role has `SELECT` only on the registry.
- Deployments behind a service mesh that terminates TLS are not supported in v1; supporting them needs a trusted
  forwarded-certificate scheme, in a later ADR.
- Two issuers cannot collide on a tenant value, because the registry key includes the issuer.
- Limits are not exact across instances; a shared limiter would put a write per request on the database being
  protected.
- Wrong-tenant reads are blocked by RLS even if a predicate is missing; composite keys are slightly wider indexes.
- `CLAUDE.md`, ADR 0013 and the proto comments carry the scope list of D5.1.

## Needs benchmark or human verification
- RLS cost on the write path (ADR 0017, capacity section).
- RFC 9068 `client_id` and RFC 8705 binding against the chosen issuer.

## Open items
- Registry cache time (D1.3) and rate-limit values (D3.1): to be set by operations.
- Per-wallet, per-account or per-class posting rights: accepted residual risk in v1; owner may revisit.

## Alternatives rejected
- **Fixed claim name in code (review).** Simpler to audit, but some issuers cannot emit arbitrary claim names.
- **Tenant id taken directly from the claim, no registry.** No way to suspend a tenant or to reject a token for a
  tenant that was never set up.
- **TLS at a proxy.** The service could not verify certificate binding, and a misconfigured proxy silently removes
  mTLS.
- **Rate limiting at a gateway only.** A security control outside this repository; the error shape would differ.
- **Schema per tenant.** Migrations multiply by tenants; connection handling changes.
- **Overdraft scope in Ledger (old 0024).** Creating accounts that may go negative needed `accounts:overdraft`
  in addition to `accounts:write`, checked once because the flag was immutable; posting stayed unrestricted.
  Superseded by the ownership boundary (owner decision 2026-11-15): Ledger creates no accounts, so the scope has
  nothing to gate. Its principle (a money source needs a privilege) and its residual posting risk are kept in D5.
- **Per-account ACLs in v1**; **type-based negative defaults**; **a separate "system account" concept in Ledger**
  (account scope now comes from the Account Service, ADR 0021 D1).
