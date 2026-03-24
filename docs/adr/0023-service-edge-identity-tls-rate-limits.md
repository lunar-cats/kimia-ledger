# ADR 0023: Service edge: tenant identity, TLS termination, rate limiting

Status: Proposed · Date: 2026-11-14 · Amends 0010, 0013

## Context
Review findings D-9, D-10. ADR 0010 says the tenant is "a JWT claim" without naming it. Nothing says which
issuers and audiences are trusted, how a tenant comes to exist or is suspended, where TLS terminates, whether
a token is bound to the client certificate, or where rate limits apply. ADR 0013 promises "fail closed"
without saying when.

## Decision

### Tokens
1. The service trusts an **allow-list of issuers**, set in deployment configuration. Per issuer: issuer URL,
   JWKS location, accepted audiences, accepted signature algorithms (asymmetric only; `none` and HMAC are
   refused), and the **tenant claim name** (default `tenant_id`).
2. Checked on every request: signature, `iss`, `aud`, `exp`, `nbf` with at most 60 s skew, presence of the
   tenant claim, and scope (`scope` claim, space-separated). `client_id` is read for audit and idempotency
   (ADR 0017); the claim name follows RFC 9068 (unverified, human to confirm against the chosen issuer).
3. **Tenant registry.** A table maps `(issuer, tenant claim value)` to an internal `tenant_id` with status
   `ACTIVE` or `SUSPENDED`. Tenants are created and suspended by an operator procedure, not by the API.
   Unknown or suspended → `PERMISSION_DENIED` (I-29). The service may cache the registry for at most a
   configured time (value to be set; it is the suspension delay).
4. **Certificate-bound tokens** (RFC 8705 `cnf` / `x5t#S256`, unverified here): required by default. A
   deployment may switch the check off per issuer; the service then logs a warning at start-up. Human
   decision whether the default stands (open question).

### TLS
5. **mTLS terminates in the service process** in v1. The service needs its certificate, key and the client
   CA bundle at start-up. No proxy or sidecar terminates TLS in a supported v1 deployment.
6. Management endpoints (health) use a separate port without ledger data (review S-1).

### Fail closed
7. The service refuses to start without: TLS material, client CA, at least one issuer with an audience, and
   a reachable tenant registry. At run time, if JWKS or the registry cannot be read and the cache is stale,
   requests fail with `UNAVAILABLE`. There is no fallback that skips a check.

### Rate limiting
8. **In the service**, per `(tenant_id, client_id)`, token bucket, in memory per instance. Limits come from
   configuration, per tenant where set. The effective cluster limit is per-instance limit × instances; the
   operator sizes for that.
9. Rate limiting runs after authentication and before idempotency. A limited request is not recorded
   (ADR 0017) and returns `RATE_LIMITED` with `RetryInfo`.
10. A gateway may add its own limits. The service does not rely on one.

## Consequences
- An operator procedure (and its database user) for the registry is needed before the first tenant exists.
  The runtime user has `SELECT` only on the registry.
- Deployments behind a service mesh that terminates TLS are not supported in v1. Supporting them needs a
  trusted forwarded-certificate scheme, in a later ADR.
- Two issuers cannot collide on a tenant value, because the registry key includes the issuer.
- Limits are not exact across instances. A shared limiter would put a write per request on the database being
  protected.

## Alternatives rejected
- **Fixed claim name in code (review).** Simpler to audit, but some issuers cannot emit arbitrary claim
  names. The name is per-issuer configuration; the check is the same code.
- **Tenant id taken directly from the claim, no registry.** No way to suspend a tenant or to reject a token
  for a tenant that was never set up.
- **TLS at a proxy.** The service could not verify certificate binding, and a misconfigured proxy silently
  removes mTLS.
- **Rate limiting at a gateway only.** The service would depend on a component outside this repository for
  a security control, and the error shape would differ.

## Relation to the review
Agrees with D-9 (allow-lists, registry, reject unknown or suspended) and settles TLS termination before the
"fail closed" promise. **Differs** on the fixed claim name. Decides D-10: in the service.
