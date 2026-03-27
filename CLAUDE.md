# Kimia Ledger — agent instructions

Open-source (Apache 2.0) double-entry ledger service. Java 25, Spring Boot 4.x, Gradle (Kotlin DSL), PostgreSQL 18.
An independently deployed Java service, separate from Sarv's .NET monolith (ADR 0014).
Correctness and security beat delivery speed. When in doubt about anything money- or security-related: **ask, do not guess.**

GitHub: https://github.com/lunar-cats/kimia-ledger · groupId / base package: `com.kimialedger`

Design status: ADRs 0014–0026 record the owner's decisions of 2026-11-15 and are **Proposed** until the owner
accepts their text. This file follows them. The design is in `docs/architecture/ledger-architecture-review.md`.

## Architecture (hexagonal, contract-first)

| Module | Package | May depend on | Rule |
|---|---|---|---|
| `contracts/` | — | — | `contracts/proto/ledger/v1/*.proto` (API) and `contracts/proto/ledger/events/v1` (Kafka, planned, not yet in the repo) are the **source of truth**. `buf lint` + `buf breaking` gate every change. |
| `ledger-core` | `com.kimialedger.core` | **nothing** (JDK only) | Pure domain: Wallet, BalanceBucket, Position, Hold, Transaction, Posting, Amount, invariants. Zero framework/annotation deps. No account lifecycle. |
| `ledger-application` | `com.kimialedger.application` | `ledger-core` | Use cases + ports (interfaces). No Spring, no JDBC, no proto types. |
| `ledger-contracts` | `com.kimialedger.contracts` | — (third-party libraries only) | Generated protobuf/gRPC stubs only (ADR 0026 D2). No project dependencies; `core` and `application` must not depend on it (ArchUnit). |
| `ledger-persistence-postgres` | `com.kimialedger.persistence.postgres` | `application`, `core` | **All SQL and all jOOQ live here and only here.** jOOQ OSS over JDBC; explicit SQL for locks, `UPDATE … RETURNING`, CTEs, advisory locks. jOOQ classes generated from the migrated schema. Flyway migrations. No JPA/Hibernate. |
| `ledger-messaging-kafka` | `com.kimialedger.messaging.kafka` | `application`, `core`, `contracts` | Outbox relay and inbox consumers. The only module with Kafka imports. |
| `ledger-account-client` | `com.kimialedger.accountclient` | `application`, `core` | gRPC client to the external Account Service. Depends on the Account Service contract stubs; where that contract lives is open (ADR 0022). |
| `ledger-api-rest` | `com.kimialedger.api.rest` | `application`, `core`, `contracts` | Thin inbound adapter. No business logic. Map proto <-> command here; limits live in application commands. |
| `ledger-api-grpc` | `com.kimialedger.api.grpc` | `application`, `core`, `contracts` | Thin inbound adapter over generated proto stubs. No business logic. Map proto <-> command here; limits live in application commands. |
| `ledger-server` | `com.kimialedger.server` | everything | Spring Boot wiring **only**. No logic. |

PENDING RENAME: the Gradle module `ledger-persistence-oracle` (package `com.kimialedger.persistence.oracle`) is the PostgreSQL persistence module until it is renamed to `ledger-persistence-postgres` (package `com.kimialedger.persistence.postgres`). The build change is not done yet. The Gradle build also lacks the three new modules (`ledger-contracts`, `ledger-messaging-kafka`, `ledger-account-client`). `ArchitectureTest` still checks `oracle..`; it is to become `org.postgresql..` and `org.jooq..` (allowed only in `persistence.postgres..`), plus a Kafka rule. The pending rename is not a reason to put SQL elsewhere.

Dependency direction is inward only and is enforced by ArchUnit tests in `ledger-server`. A failing ArchUnit test is a design bug, not a test to loosen.

## Ownership (ADR 0021)

| Concept | Owner |
|---|---|
| Wallet, BalanceBucket, Bucket-to-Account association, Hold, Journal Transaction, Posting | **Ledger** |
| Account, account id, classification, lifecycle, overdraft policy | **External Account Service** |
| Payment Intent, Provider Attempt, Payment Timeline | **Payments / Integration / Operations** |

Ledger has **no authoritative Account table and no account lifecycle API**. It keeps a non-authoritative
`account_state_cache` and immutable `account_snapshots`. Ledger never invents an external account id. Wallets
and buckets are authoritative in Ledger and are never deleted.

## Ledger model — the non-negotiables

- **N-leg journal transactions**: a Transaction has ≥ 2 Postings. Invariant: `Σ postings = 0` **per asset** within one transaction.
- **Positions**: a posting hits one position: a customer bucket, or a direct `(system account, asset)` position. For customers, one bucket per `(tenant_id, external_account_id, asset_code)`; no pooling of customer balances.
- **Money = integer minor units** as `NUMERIC(38,0)` / `BigInteger`, plus an asset code with an immutable scale. **Never `float`/`double`; no `BigDecimal` for money amounts.** FX rates are exact decimals, only inside `core.fx`. API amounts are decimal strings.
- **Append-only journal**: postings and transactions are never updated or deleted. Corrections are reversals or approved adjustments. Positions are a mutable projection updated only together with postings.
- **Write path is synchronous and atomic**: account lookup before the transaction (never under locks) → one DB transaction: advisory lock on the idempotency key and lookup → lock account anchors (`FOR KEY SHARE`), wallets (lifecycle operations only), positions (`FOR UPDATE`), holds, settlement batches in the global sorted order (I-17) → validate in core → insert transaction and postings (`position_seq`, `balance_after`) → update positions → outbox rows → idempotency record last → commit. Events go out asynchronously via the transactional outbox.
- **Wallet states** `PROVISIONING, ACTIVE, SUSPENDED, FROZEN, CLOSING, CLOSED` with the operation matrix of ADR 0021. Transaction origin (`CLIENT`, `SYSTEM`, `EXCEPTIONAL`) comes from token scopes, never from the request.
- **Native holds** on bucket positions; over-capture rejected; release always possible; no hold TTL constant in code.
- **Idempotency key required on every write**, unique per `(tenant_id, idempotency_key)`; a duplicate returns the original result; a duplicate with a *different* payload is `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`.
- **Multi-tenant** via `tenant_id` on every table and every query, from day one; RLS is the second layer.
- **No PII in the ledger.** Only opaque `external_ref`, `owner_ref` and `external_account_id`.
- Invariants are stated precisely in `docs/domain/invariants.md`. Code that cannot cite an invariant from that file is suspect.

## Security rules (apply to every diff; checked by `security-reviewer` against `docs/security/checklist.md`)

- Transport/authn: **mTLS + OAuth2 client-credentials** (JWT). Scopes: `wallets:read`, `wallets:write`, `wallets:freeze`, `transactions:read`, `transactions:write`, `transactions:system`, `transactions:exceptional`, `holds:read`, `holds:write`, `balances:read`, `postings:read`, `fx:write`, `settlement:write`, `reconciliation:*`, `accounts:fence` (Account Service only). Tenant isolation on **every** query — the tenant comes from the token, never from the request body.
- DB least privilege (ADR 0014): runtime role has **SELECT/INSERT only** on append-only tables and SELECT/INSERT/UPDATE on state tables; **no DELETE or TRUNCATE anywhere** except the cleanup role's DELETE of old delivery rows (ADR 0024 D7). Triggers refuse mutation of append-only tables. Migrations run as a **separate** role; the relay (publish path: UPDATE of three columns, no DELETE) and the cleanup job (role `ledger_cleanup`: DELETE of old published outbox rows and old inbox rows) each have their own role (ADR 0024 D7). The break-glass role `ledger_repair` may only INSERT quarantine records for unpublished outbox rows; it never deletes events (ADR 0024 D7.12).
- Secrets only from env or Vault. **Nothing secret in the repo, in logs, in test fixtures, or in error messages.**
- Input limits: request size, max postings per transaction, max page size, rate limiting. Every list endpoint paginates.
- Audit trail: `client_id`, `request_id`, `correlation_id`, `idempotency_key`, `origin`, `approval_ref` recorded on every write.
- Container: distroless, non-root, cosign-signed images, pinned (hash or exact-version) dependencies and actions.
- Supply chain: pin exact versions (verify the current version before pinning), Renovate for updates, CycloneDX SBOM, dependency review in CI.

## Commands

```bash
./gradlew build                 # compile + all tests (incl. ArchUnit, Testcontainers)
./gradlew test                  # unit + arch tests
./gradlew spotlessApply         # format
./gradlew spotlessCheck         # format check (pre-commit runs this)
./gradlew :ledger-server:bootRun
cd contracts && buf lint && buf format -d --exit-code && buf generate   # OpenAPI is committed; regenerate after any proto change
cd contracts && buf breaking --against '../.git#branch=main,subdir=contracts'   # fails on any breaking change in ledger.v1
gitleaks protect --staged       # secret scan (pre-commit runs this)
git config core.hooksPath .githooks   # one-time, enables the pre-commit hook
```

Testcontainers needs a reachable Docker daemon. Database image: official `postgres:18`, pinned by exact tag and digest.

## Conventions

- Java 25, records for values, sealed interfaces for closed hierarchies, `Optional` only as return type.
- Errors: domain failures are typed (`LedgerError` enum mirrored 1:1 by the proto `ErrorCode`); never throw generic `RuntimeException` across a module boundary.
- Tests: JUnit 5, AssertJ, jqwik for properties (every invariant gets a property test), Testcontainers for PostgreSQL, ArchUnit for structure. Test names describe behaviour.
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
- Adding a dependency without checking its license (Apache-2.0 / MIT / BSD / EPL only; jOOQ only as the OSS edition; no GPL) and its current version.
- Floating-point money, mutable postings, UPDATE/DELETE on append-only tables, DELETE on any ledger table (except the cleanup role on published outbox rows older than the retention and old inbox rows), SQL or jOOQ outside `ledger-persistence-postgres`, JPA/Hibernate on the write path, framework imports in `ledger-core`/`ledger-application`.
- Calling the Account Service (or any network service) while a database transaction is open.
- Creating or binding an account from a client request (bucket-account associations come only from Account Service output; a system account may be a posting target by id only after it resolves to a SYSTEM account, ADR 0021 D1.4); taking an overdraft limit or transaction origin from a client request.
- Logging request bodies, tokens, or anything that could be PII.
- Loosening an ArchUnit rule, a `buf breaking` check, or a security test to make a build green.
