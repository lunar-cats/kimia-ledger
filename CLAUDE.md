# Kimia Ledger — agent instructions

Open-source (Apache 2.0) double-entry ledger service. Java 25, Spring Boot, Gradle (Kotlin DSL), Oracle.
Correctness and security beat delivery speed. When in doubt about anything money- or security-related: **ask, do not guess.**

GitHub: https://github.com/lunar-cats/kimia-ledger · groupId / base package: `com.kimialedger`

## Architecture (hexagonal, contract-first)

| Module | Package | May depend on | Rule |
|---|---|---|---|
| `contracts/` | — | — | `ledger/v1/*.proto` is the **source of truth**. REST is gRPC-JSON transcoding (`google.api.http`); OpenAPI is generated, never hand-edited. `buf lint` + `buf breaking` gate every change. |
| `ledger-core` | `com.kimialedger.core` | **nothing** (JDK only) | Pure domain: Account, Entry, Posting, Money, invariants. Zero framework/annotation deps. |
| `ledger-application` | `com.kimialedger.application` | `ledger-core` | Use cases + ports (interfaces). No Spring, no JDBC, no proto types. |
| `ledger-persistence-oracle` | `com.kimialedger.persistence.oracle` | `application`, `core` | **All SQL lives here and only here.** Plain JDBC / `JdbcClient`. Flyway migrations. No jOOQ (commercial with Oracle). |
| `ledger-api-rest` | `com.kimialedger.api.rest` | `application`, `core` | Thin inbound adapter. Maps DTO ↔ use case. No business logic. |
| `ledger-api-grpc` | `com.kimialedger.api.grpc` | `application`, `core` | Thin inbound adapter over generated proto stubs. No business logic. |
| `ledger-server` | `com.kimialedger.server` | everything | Spring Boot wiring **only**. No logic. |

Dependency direction is inward only and is enforced by ArchUnit tests in `ledger-server`. A failing ArchUnit test is a design bug, not a test to loosen.

## Ledger model — the non-negotiables

- **N-leg journal entries**: an Entry has ≥ 2 Postings. Invariant: `Σ postings = 0` **per currency** within one entry.
- Account types: `ASSET, LIABILITY, EQUITY, INCOME, EXPENSE`, each with a normal balance side.
- **Money = integer minor units** (`long` / `NUMBER(38,0)`) + ISO-4217 currency. **Never `float`/`double`/`BigDecimal` with scale ≠ 0.** API amounts are `string` or `int64`.
- **Append-only**: postings are never updated or deleted. Corrections are reversing entries.
- **Write path is synchronous and atomic**: one DB transaction — lock accounts in deterministic (sorted id) order with `SELECT … FOR UPDATE` → validate → insert postings → update balances → insert outbox row. Events go out asynchronously via the transactional outbox.
- **Idempotency key required on every write**, unique per `(tenant_id, idempotency_key)`; a duplicate returns the original result; a duplicate with a *different* payload is `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`.
- **Multi-tenant** via `tenant_id` on every table and every query, from day one.
- **No PII in the ledger.** Only opaque `external_ref` identifiers.
- Invariants are stated precisely in `docs/domain/invariants.md`. Code that cannot cite an invariant from that file is suspect.

## Security rules (apply to every diff; checked by `security-reviewer` against `docs/security/checklist.md`)

- Transport/authn: **mTLS + OAuth2 client-credentials** (JWT). Scopes are fine-grained: `accounts:write`, `accounts:read`, `entries:write`, `entries:read`, `balances:read`, `postings:read`. Tenant isolation on **every** query — the tenant comes from the token, never from the request body.
- DB least privilege: the runtime app user has **INSERT/SELECT only** on journal tables (no UPDATE/DELETE). Migrations run as a **separate** user. Plan for Oracle Immutable / Blockchain tables for the journal.
- Secrets only from env or Vault. **Nothing secret in the repo, in logs, in test fixtures, or in error messages.**
- Input limits: request size, max postings per entry, max page size, rate limiting. Every list endpoint paginates.
- Audit trail: `client_id`, `request_id`, `correlation_id`, `idempotency_key` recorded on every write.
- Container: distroless, non-root, cosign-signed images, pinned (hash or exact-version) dependencies and actions.
- Supply chain: pin exact versions (verify the current version before pinning), Renovate for updates, CycloneDX SBOM, dependency review in CI.

## Commands

```bash
./gradlew build                 # compile + all tests (incl. ArchUnit, Testcontainers)
./gradlew test                  # unit + arch tests
./gradlew spotlessApply         # format
./gradlew spotlessCheck         # format check (pre-commit runs this)
./gradlew :ledger-server:bootRun
buf lint && buf breaking --against '.git#branch=main,subdir=contracts'   # run inside contracts/
gitleaks protect --staged       # secret scan (pre-commit runs this)
git config core.hooksPath .githooks   # one-time, enables the pre-commit hook
```

Testcontainers needs a reachable Docker daemon. Oracle image: `gvenzl/oracle-free` slim-faststart variant.

## Conventions

- Java 25, records for values, sealed interfaces for closed hierarchies, `Optional` only as return type.
- Errors: domain failures are typed (`LedgerError` enum mirrored 1:1 by the proto `ErrorCode`); never throw generic `RuntimeException` across a module boundary.
- Tests: JUnit 5, AssertJ, jqwik for properties (every invariant gets a property test), Testcontainers for Oracle, ArchUnit for structure. Test names describe behaviour.
- Commits: Conventional Commits (`feat(core): …`, `fix(persistence): …`, `docs: …`, `chore: …`).
  **Chunk commits**: one logical concern per commit (build, feature, tests, docs), never one commit per step or PR.
  **No attribution trailers**: never add `Co-Authored-By`, `Generated with`, or any AI/tool signature to commits or PR text.
- ADRs in `docs/adr/NNNN-title.md` (context / decision / consequences). A decision without an ADR did not happen.
- Keep CHANGELOG.md (Keep-a-Changelog format) current in the same PR as the change.

## Workflow per task

`architect` plan → dev agent (`domain-dev` or `api-dev`) → `test-engineer` → `security-reviewer` → `docs-writer` → `git-committer` → **human approval before merge**.
Stop at every CHECKPOINT the human defined. Summarize: what changed, files, how to verify.

## Forbidden

- Pushing to any remote. Committing to `main`. Hard resets, recursive force-deletes, piping downloads into a shell.
- Editing `.github/workflows/**` without asking first.
- Adding a dependency without checking its license (Apache-2.0 / MIT / BSD / EPL only; **no jOOQ**, no GPL) and its current version.
- Floating-point money, mutable postings, UPDATE/DELETE on journal tables, SQL outside `ledger-persistence-oracle`, framework imports in `ledger-core`/`ledger-application`.
- Logging request bodies, tokens, or anything that could be PII.
- Loosening an ArchUnit rule, a `buf breaking` check, or a security test to make a build green.
