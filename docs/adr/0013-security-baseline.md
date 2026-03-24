# ADR 0013: Security baseline

Status: Accepted · Date: 2026-11-13

Amended by ADR 0020, ADR 0021, ADR 0023, ADR 0024 (Proposed). The text below is unchanged; where it conflicts with an accepted amendment, the amendment wins.

## Context
A ledger is a high-value target. These controls are decided up front so later work cannot trade them away.

## Decision
- **Transport and identity**: mTLS between services; OAuth2 client-credentials with JWT access tokens.
  Scopes: `accounts:read`, `accounts:write`, `entries:read`, `entries:write`, `balances:read`, `postings:read`.
  Tenant comes from the token (ADR 0010).
- **Database least privilege**: runtime user INSERT/SELECT only on journal tables; separate migration user;
  separate outbox relay user with UPDATE on the outbox only. Journal planned as Immutable/Blockchain tables.
- **Secrets** only from environment or Vault. Nothing secret in the repository, logs, fixtures or error messages. gitleaks in pre-commit and CI.
- **Input limits**: maximum request size, maximum postings per entry, maximum page size, string length limits, rate limiting per client.
- **Audit trail**: `client_id`, `request_id`, `correlation_id`, `idempotency_key` stored with every write.
- **Supply chain**: exact-version pins, Renovate, dependency review, CycloneDX SBOM, CodeQL, SHA-pinned GitHub Actions, cosign-signed distroless non-root images.
- A **security review** against `docs/security/checklist.md` is part of every task.

## Consequences
- Local development needs a token issuer (or a dev profile with a static test key, never in production).
- Some controls (mTLS, Vault) depend on the deployment platform; the service must fail closed when they are absent.
