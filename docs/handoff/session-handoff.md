# Session handoff: Kimia Ledger architecture decisions

Date of the session: 2026-11-14. Status at writing: all work is **uncommitted** on branch `docs/arch-decisions`.

This file is for the repo owner on another machine, or for a fresh Claude Code session with no memory of the earlier session. It lists what was done, what was checked, what is not done, and what the owner must decide.

## 1. Resume here

### 1.1 Where the work is

| Item | Value |
|---|---|
| Local branch | `docs/arch-decisions` |
| Base commit | `7604c2b` (merge of `chore/bootstrap` into `main`). The branch was created from `main` at this commit. |
| Remote | `origin` = `git@github.com:lunar-cats/kimia-ledger.git` |
| Remote branches seen | `origin/main`, `origin/chore/bootstrap` |
| Commits on this branch since `7604c2b` | 7: `a1c29bc`, `51e1ae8`, `6665efe`, `b990ab8`, `b5b95ff`, `cc9ff32`, `a257d34`. They hold the first draft ADRs (old 0014 to 0032), the invariants, the threat model, the review and an earlier handoff. |
| Uncommitted files | 60 entries in `git status` at the end of the 2026-11-15 semantic pass: 28 modified, 19 deleted (old draft files 0014 to 0032), 13 new (ADRs 0014 to 0026). Section 4.7 lists the pass. |

Before you start on a new machine:

1. Clone the repo and check out `docs/arch-decisions`.
2. Check whether the branch was pushed. The consolidated ADRs 0014 to 0026, the index and the 2026-11-15 changes (section 4.7) are uncommitted, and so are later edits to the files in the seven commits above. If the work was never pushed, it exists only on the old machine. Copy the working tree from there first.
3. Run `git status` and compare with the counts above. The counts are from the end of the 2026-11-15 semantic pass and change as files are committed.

### 1.2 First message for a new Claude Code session

Use this message. It points to the sections that exist in the review file.

> Read docs/handoff/session-handoff.md, then docs/architecture/ledger-architecture-review.md sections 20 and 21, and continue with the next steps in section 7 of this handoff.

Note: the first draft of this message said "section 7 (Next steps)" of the review. The review has no such section. Section 7 of the review is the database schema. Next steps are in sections 20 and 21 of the review, and in section 7 of this handoff. See Section 11 (Corrections).

### 1.3 Two rule files that a new machine needs

These files are separate. The global file is not in the repo, so it does not travel with a clone.

| File | Where | What it holds |
|---|---|---|
| `CLAUDE.md` | repo root | Project rules for agents. Rewritten to PostgreSQL 18 on 2026-11-15, uncommitted (section 9). |
| `CLAUDE.md` (global) | `~/.claude/CLAUDE.md` (Windows: `C:\Users\<user>\.claude\CLAUDE.md`) | Owner's delegation rules for all projects. |

The global delegation rules are:

| Task | Delegate to |
|---|---|
| Commit | subagent `committer`. Push only if asked, and then push yourself. |
| Tests for specific code | subagent `test-writer` |
| Write or update an `.md` file | subagent `doc-writer` |
| Everything else | main session |
| Model | the owner chooses it with `/model` |

## 2. Project and rules

Kimia Ledger is an open-source (Apache 2.0) double-entry ledger service. Stack (`CLAUDE.md` as of the 2026-11-15 pass): Java 25, Spring Boot 4.x, Gradle (Kotlin DSL), PostgreSQL 18 with jOOQ (ADR 0014). Oracle is dropped in the design; ADR 0002 (Accepted) is replaced by ADR 0014 (Proposed).

### 2.1 State of the code

- Pre-alpha. Phase 1: skeleton, contracts and docs (`README.md`). Not for production use.
- The Gradle build still names the persistence module `ledger-persistence-oracle` (PENDING RENAME to `ledger-persistence-postgres`). It does not yet include `ledger-contracts`, `ledger-messaging-kafka` or `ledger-account-client`.
- `ArchitectureTest` still checks `oracle..`. It is to become `org.postgresql..` and `org.jooq..` (only in `persistence.postgres..`), plus a Kafka rule.
- The module skeleton and a `/health` endpoint exist. There is no domain code, no persistence code, no Flyway migration and no jOOQ class yet.
- Contracts: `contracts/proto/ledger/v1/` holds the draft `ledger.v1`, which still uses the old error names (section 4.7). `contracts/proto/ledger/events/v1` does not exist yet; its envelope is decided (section 4.7, item 12), the business payloads are not.

### 2.2 Repo rules (`CLAUDE.md`, as of the 2026-11-15 pass)

`CLAUDE.md` is the rules file for agents. It follows ADRs 0014 to 0026, which are Proposed until the owner accepts their text. Uncommitted at the time of writing.

| Rule | Detail |
|---|---|
| Design status | ADRs 0014 to 0026 record the owner's 2026-11-15 decisions and are Proposed. `CLAUDE.md` follows them. |
| Module layout | Hexagonal, inward dependencies only, checked by ArchUnit in `ledger-server`. `ledger-core`: JDK only. `ledger-application`: `core` only; no Spring, JDBC or proto types. `ledger-persistence-postgres`: `application`, `core`; all SQL and all jOOQ live here and only here. `ledger-messaging-kafka`: `application`, `core`, `contracts`; the only module with Kafka imports. `ledger-account-client`: `application`, `core`; gRPC client to the Account Service. `ledger-api-rest` and `ledger-api-grpc`: `application`, `core`, `contracts`; proto-to-command mapping in each adapter, limits in the application commands. `ledger-contracts`: generated stubs only, no project dependencies (ADR 0026 D2). `ledger-server`: Spring Boot wiring only. |
| Ownership | Ledger owns Wallet, BalanceBucket, the bucket-to-account association, Hold, Transaction and Posting. The external Account Service owns Account, account id, classification, lifecycle and overdraft policy. Ledger has no authoritative account table and never invents an external account id (ADR 0021). |
| Money | Integer minor units as `NUMERIC(38,0)` / `BigInteger`, with an asset code that has an immutable scale. No `float` or `double`, and no `BigDecimal` for amounts. FX rates are exact decimals, only in `core.fx`. Wire amounts are decimal strings (ADR 0015). |
| Journal | Append-only. Postings and transactions are never updated or deleted. Corrections are reversals or approved adjustments. Positions are a projection, updated only together with postings (ADR 0016, 0017). |
| Write path | Account lookup before the transaction, never under locks. One database transaction: advisory lock on the idempotency key and lookup; `FOR KEY SHARE` on account anchors; wallets (lifecycle operations only); `FOR UPDATE` on positions; holds; settlement batches, in one global sorted order (I-17). Then validate in core, insert transaction and postings, update positions, insert outbox rows, record the idempotency record last, commit. Events leave through the transactional outbox (ADR 0017 D5, ADR 0024). |
| Wallet states and origin | `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `FROZEN`, `CLOSING`, `CLOSED`, with the operation matrix of ADR 0021 D4. Origin (`CLIENT`, `SYSTEM`, `EXCEPTIONAL`) comes from token scopes, never from the request. |
| Holds | Native holds on bucket positions. Over-capture is rejected. Release is always possible. No hold TTL constant in code (ADR 0023). |
| Idempotency | Required on every write, unique per `(tenant_id, idempotency_key)`. The same payload returns the original result. A different payload returns `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT` (ADR 0018). |
| Tenancy and PII | `tenant_id` on every table and query, from day one; RLS is the second layer (ADR 0010, 0020). No personal data: only opaque `external_ref`, `owner_ref` and `external_account_id`. |
| Security | mTLS and OAuth2 client credentials (JWT). The tenant comes from the token, never from the body. Runtime role: SELECT and INSERT on append-only tables; SELECT, INSERT and UPDATE on state tables; no DELETE or TRUNCATE. Migrations run as a separate role; the relay has its own role (ADR 0014, 0020). Checked against `docs/security/checklist.md`. |
| Tests | JUnit 5, AssertJ, jqwik (every invariant gets a property test), Testcontainers for PostgreSQL (`postgres:18`, pinned by tag and digest), ArchUnit. |
| Commits, ADRs, CHANGELOG | Conventional Commits, one logical concern per commit, no attribution trailers (per `CLAUDE.md`). ADRs in `docs/adr/NNNN-title.md`. `CHANGELOG.md` updated in the same change. |
| Workflow | `architect` plan, then `domain-dev` or `api-dev`, then `test-engineer`, `security-reviewer`, `docs-writer`, `git-committer`, then human approval before merge. Stop at every human checkpoint. |

Forbidden (`CLAUDE.md`, current list):

- Pushing to any remote. Committing to `main`. Hard resets, recursive force-deletes, piping downloads into a shell.
- Editing `.github/workflows/**` without asking first.
- Adding a dependency without checking its licence (Apache-2.0, MIT, BSD or EPL only; jOOQ only as the OSS edition; no GPL) and its current version.
- Floating-point money, mutable postings, UPDATE or DELETE on append-only tables, DELETE on any ledger table, SQL or jOOQ outside `ledger-persistence-postgres`, JPA or Hibernate on the write path, framework imports in `ledger-core` or `ledger-application`.
- Calling the Account Service, or any network service, while a database transaction is open.
- Creating or binding an account from a client request. A system account may be a posting target by id only after it resolves to a SYSTEM account (ADR 0021 D1.4). Taking an overdraft limit or a transaction origin from a client request.
- Logging request bodies, tokens, or anything that could be PII.
- Loosening an ArchUnit rule, a `buf breaking` check, or a security test to make a build green.

### 2.3 Local permissions (`.claude/settings.json`, checked in)

| Setting | Effect |
|---|---|
| `deny`: `Bash(git push:*)`, `Bash(git push --force:*)`, `Bash(git reset --hard:*)`, `rm -rf`, `rm -fr`, pipes from `curl` or `wget` into a shell | Blocked. |
| `ask`: `Edit`/`Write` on `.github/workflows/**`, `Bash(git commit:*)` | Asks before running. |

Result: the owner must push by hand, or lift the `git push` deny rule.

## 3. Agents

### 3.1 Project agents (`.claude/agents/`, as of the 2026-11-15 pass)

Uncommitted at the time of writing. Models and tools are from each file's frontmatter.

| Agent | Model | Tools | What it does now |
|---|---|---|---|
| `architect` | inherit | Read, Grep, Glob | Read-only. Writes the plan: modules, invariants, ports, the ADR to write, test plan, security notes. Drafts ADR text for the caller to save; it cannot save files itself. |
| `domain-dev` | inherit | Read, Grep, Glob, Edit, Write, Bash | Implements `ledger-core`, `ledger-application` and `ledger-persistence-postgres` (PENDING RENAME from `ledger-persistence-oracle`; the build still uses the old name). Runs `./gradlew build` before reporting. |
| `api-dev` | sonnet | Read, Grep, Glob, Edit, Write, Bash | Owns `contracts/` and the REST and gRPC adapters. The proto is the source; REST follows the `google.api.http` mapping (ADR 0019 D1). Runs `buf lint` and `buf breaking` before reporting. |
| `test-engineer` | sonnet | Read, Grep, Glob, Edit, Write, Bash | Writes and runs jqwik, concurrency, ArchUnit and Testcontainers tests. Each touched invariant gets a property test. |
| `security-reviewer` | inherit | Read, Grep, Glob, Bash | Read-only. Checks the diff against `docs/security/checklist.md` and the threat model, with file and line evidence. |
| `docs-writer` | haiku | Read, Grep, Glob, Edit, Write | Writes markdown only: `docs/**`, root `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md` and other root `*.md`. Never touches source, build files, contracts, workflows or `.claude/`. |
| `git-committer` | haiku | Bash, Read, Grep, Glob | Git only. Stages named paths only, one concern per commit. Never pushes and never commits to `main`. |

### 3.2 Global agents (`~/.claude/agents/`)

| Agent | Model | Tools |
|---|---|---|
| `committer` | haiku | Bash, Read, Grep, Glob |
| `doc-writer` | haiku | Bash, Read, Grep, Glob, Edit, Write |
| `test-writer` | haiku | Bash, Read, Grep, Glob, Edit, Write |

### 3.3 Known problem: two agent sets overlap

| Task | Global agent (owner's rules) | Project agent (repo workflow) |
|---|---|---|
| Commit | `committer` | `git-committer` |
| Docs | `doc-writer` | `docs-writer` |
| Tests | `test-writer` | `test-engineer` |

Status: not resolved. The owner has not chosen one set. Prompt drift is listed in `docs/review/2026-11-14-architecture-review.md` section A.

## 4. What was done

### 4.1 Artifacts

| Path | What | Status |
|---|---|---|
| `docs/architecture/ledger-architecture-review.md` | Final review in Persian. 21 sections, about 1,930 lines. Ownership, Wallet/Bucket/Account mapping, DDL, flows, Kafka, REST/gRPC, ADR verdicts, open decisions, recommendation. | Committed in `b5b95ff`; later edits uncommitted. Proposal only. No ADR was set to Accepted. |
| `docs/adr/0014-…` to `docs/adr/0032-…` (old draft files) | 19 new ADRs (see 4.2) | Old drafts 0014 to 0032 committed in `a1c29bc`, all `Proposed`. On 2026-11-15 they were consolidated into ADRs 0014–0026 (13 new files, uncommitted); the old files are deleted in the working tree. See the consolidation map in `docs/adr/README.md`. |
| `docs/adr/0002`, `0004`, `0005`, `0006`, `0007`, `0008`, `0009`, `0010`, `0011`, `0013` | Each has an `Amended by` line. | Amended-by lines committed in `51e1ae8` (0002, 0004 to 0011, 0013). Later edits uncommitted, including the lines on 0003 and 0012. Status not changed. |
| `docs/adr/README.md` | Index updated for the new ADRs | Index for the drafts committed in `a1c29bc`. The consolidated index is uncommitted. |
| `docs/domain/invariants.md` | I-18 is now per account. I-22 to I-30 added. | Committed in `6665efe`. Later edits uncommitted. |
| `docs/security/threat-model.md`, `docs/security/checklist.md` | Updated for the review | Committed in `b990ab8`. Later edits uncommitted. |
| `CHANGELOG.md` | Updated | One line committed in `a257d34`. Later edits uncommitted. |
| `docs/review/2026-11-14-architecture-review.md` | Earlier review (findings B, D, P, A, T, S). | Already tracked, committed in `acd881f`. Not changed in this session. |
| `docs/handoff/session-handoff.md` | This file | First committed in `cc9ff32`. Later edits uncommitted. |

### 4.2 ADR titles, old draft numbers 0014 to 0032

These are the draft numbers of this session. On 2026-11-15 the Proposed drafts were consolidated into ADRs 0014–0026; the last column gives the ADR that now holds each decision (map in `docs/adr/README.md`).

| Old ADR | Title (from the file) | Amends (from the file header) | Now in |
|---|---|---|---|
| old 0014 | Per-account posting sequence; no tenant-wide journal order | none | 0017 |
| old 0015 | REST is a hand-written adapter that conforms to the gRPC-JSON transcoding mapping | 0004 | 0019 |
| old 0016 | Entry model: effective time, server-built reversals, repeated accounts, input message | 0005, 0007 | 0016 |
| old 0017 | Idempotency semantics: one key, one outcome | 0009 | 0018 |
| old 0018 | Error contract, status mapping and correlation id | 0004 | 0019 |
| old 0019 | Money range, storage width, currency column and exponent | 0002, 0006 | 0015 |
| old 0020 | Write path with insert-only running balances | 0005, 0007, 0008, 0013 | 0017 |
| old 0021 | No events and no outbox in v1 | 0008, 0013 | 0024 |
| old 0022 | No native holds in v1; holds are modelled with a hold account | none | 0023 |
| old 0023 | Service edge: tenant identity, TLS termination, rate limiting | 0010, 0013 | 0020 |
| old 0024 | A separate scope to create accounts that may go negative | 0013 | 0020 |
| old 0025 | Account lifecycle, lookup and caller-supplied fields | 0011 | 0021 |
| old 0026 | Timeouts, per-tenant concurrency cap and hot accounts | none | 0017 |
| old 0027 | Tenant isolation in the database: composite keys now, row policies later | 0010 | 0020 |
| old 0028 | Oracle only in v1; driver licence exception; immutable tables before production | 0002 | 0014 |
| old 0029 | Reconciliation, account freeze, recovery and retention | 0007 | 0025 |
| old 0030 | Transaction port in ledger-application | none | 0026 |
| old 0031 | ledger-contracts module for generated Java and the proto mapping | none | 0026 |
| old 0032 | Contract freeze, breaking-change baseline and client tolerance | 0004 | 0019 |

All 19 were committed in `a1c29bc` as `Proposed`, dated 2026-11-14. On 2026-11-15 they were consolidated into 0014–0026 (uncommitted); the old files are deleted in the working tree.

### 4.3 Review verdicts on the 19 ADRs (review section 19.1; old draft numbers)

| Verdict | Count | ADRs |
|---|---|---|
| ACCEPT | 6 | old 0015, old 0023, old 0026, old 0030, old 0031, old 0032 |
| REVISE | 9 | old 0014, old 0016, old 0017, old 0018, old 0019, old 0024, old 0025, old 0027, old 0029 |
| REJECT | 4 | old 0020, old 0021, old 0022, old 0028 |
| DEFER | 0 | none |

ADR old 0025 is split. Wallet and bucket lifecycle stays in Ledger. Account lifecycle moves to the Account Service.

No ADR status was changed to Accepted.

### 4.4 Design audit (external, not saved in the repo)

An independent system-design audit ran on the Fable model at extra-high effort. It was read-only and produced a report only. The report is not in the repo.

- Verdict: fundamentals are sound. The design is not yet as good as it can be.
- Grades: correctness adequate, security adequate, API contract adequate, operability weak, evolvability adequate.
- Main findings, R-1 to R-20. The top ones:

| ID | Finding |
|---|---|
| R-1 | A tenant-wide `sequence_no` is unsafe for cursor paging, because commit order is not sequence order. |
| R-2 | Balance rows are updated, but the runtime user is INSERT and SELECT only. |
| R-3 | Rejected requests should be recorded under the idempotency key. |
| R-4 | No timeouts and no capacity target. A hot fee account serializes a whole tenant. |
| R-5 | Authorization stops at tenant and RPC. A client with write scopes can create an overdraftable account. |
| R-7 | No RPO, DR or archival plan. |
| R-9 | Narrowing balances to `NUMBER(19,0)` was wrong. |
| R-10 | The claim "double-entry enforced at DB level" had no enforcement behind it. |
| R-12 | Conflict code mapping. |
| R-13 | Closed enums break JSON clients. |
| R-14 | Payload hash is not defined. |

The audit also said the earlier review was wrong about B-9, B-7 and B-8, and that B-1, D-1 and D-5 conflict.

Its five decisions to make before code:

1. Ordering and balance storage.
2. Capacity target and contention policy.
3. Idempotency in full.
4. Privilege model as tables.
5. Platform scope and freeze rules.

### 4.5 Model and baseline changes during the session

- The owner gave a new baseline from another AI tool: Java 25, Spring Boot 4.x, PostgreSQL 18, jOOQ with JDBC, Flyway, Kafka 4.x, REST and gRPC, modular monolith, light CQRS, transactional outbox and inbox, mutable transactional balance projection, native holds. Ledger does not own Accounts.
- Addendum from the owner: Ledger owns Wallets and BalanceBuckets. Accounts, including system accounts, are owned by an external Account Service.
- Payment Intent belongs to the Payments service. Payment Provider Attempt belongs to Payments or Integration. Payment Operational Timeline belongs to Payments or Operations.

### 4.6 Checks that were run and not run

| Check | Result |
|---|---|
| DDL in review section 7.3 on a throwaway `postgres:18` container (PostgreSQL 18.7) | Ran clean with `ON_ERROR_STOP=1`. 24 tables, 43 non-internal triggers, 60 policies. Container removed. |
| Manual smoke test of the DDL | 15 functional scenarios passed. No concurrency, no load. |
| DDL revised for the final owner decisions, on a fresh throwaway `postgres:18` container (PostgreSQL 18.7), 2026-11-15 | Ran clean with `ON_ERROR_STOP=1`. 32 tables, 65 non-internal triggers, 86 policies, 67 indexes. 22 functional scenarios with 35 checks, all passed (review section 2.2). Run twice; the second run followed comment-only changes. Container removed. The two rows above are historical. |
| `./gradlew test --offline` | Failed at configuration. The Spotless plugin was not in the local cache. Not a code problem. |
| `./gradlew test` (online, background) | Dependencies downloaded. All modules compiled. `:ledger-server:test` produced no output for about 35 minutes and was stopped. Cause unknown. |
| `buf lint`, `buf breaking` | Not run. `buf` is not installed. |
| Concurrency, load, Flyway, jOOQ, Kafka tests | Not run. |

The rows above describe the state before section 4.7. The later checks are in section 4.7.

Machine notes:

- JDK installed: 26.0.1. The build uses a Java 25 toolchain.
- Two `java.exe` processes started 14:48 and 14:49 may be leftover Gradle daemons. They were not killed.

### 4.7 Semantic review pass (2026-11-15, after the consolidation to ADRs 0014-0026)

What was done: every ADR body from 0001 to 0026 was read and cross-checked against `docs/domain/invariants.md`, the threat model, the checklist, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, the integration guide, the contracts README and the agent prompts. Status lines did not change: 0001 to 0013 are Accepted and 0014 to 0026 are Proposed. No ADR was accepted in this pass. All changes in this pass are uncommitted.

Issues found and fixed:

1. ADR 0019 error table mixed old and new names. Now: `ASSET_MISMATCH` (was `CURRENCY_MISMATCH`); `ACCOUNT_UNKNOWN` (was `ACCOUNT_NOT_FOUND`, owner decision); `TRANSACTION_NOT_FOUND`, `TRANSACTION_ALREADY_REVERSED` and `TRANSACTION_NOT_REVERSIBLE` (were `ENTRY_*`); `ACCOUNT_CLOSED` and `ACCOUNT_FROZEN` retired in favour of `ACCOUNT_RESTRICTED` and `WALLET_FROZEN`. `INVALID_ENTRY`, `UNBALANCED_ENTRY` and `TOO_MANY_POSTINGS` keep their names. D2.9 "only appended" applies from `v1.0.0`; before that, the renames under D2 are allowed. The draft protos still carry the old names until the rewrite.
2. The account-id rule contradicted itself. ADR 0021 D1.4 and `CLAUDE.md` said a client can never supply an account id, but a posting names a SYSTEM account by `external_account_id` (ADR 0016 D1.2). Now: Ledger never creates or binds an account on a client's say-so; a system account target must resolve to a `SYSTEM` account at the Account Service, else `ACCOUNT_UNKNOWN`; customer account ids are never posting targets. The `CLAUDE.md` Forbidden list and checklist D7 are aligned.
3. ADR 0026 D2 changed. `ledger-contracts` now holds generated stubs only and depends on no project module. The mapping between proto messages and use-case commands lives in each inbound adapter (ADR 0003). Limits and validation live in the application commands. Drift is guarded by the ADR 0019 D1.7 conformance tests. `ledger-messaging-kafka` depends on `ledger-contracts` for the events stubs. A shared mapping module is the documented fallback (D2.5). Reason: the earlier design had `ledger-contracts` depend on `ledger-application` and `ledger-core`. That contradicted accepted ADR 0003 and pulled use-case code into the Kafka and account-client modules. The `CLAUDE.md` module table, the README tree and review section 6 are aligned. This is a recommendation applied to a Proposed ADR; the owner may revert it.
4. ADR 0019 follow-up "fix the `buf breaking` path (S-5)" was stale. `CLAUDE.md` and `contracts/README.md` already use `'../.git#branch=main,subdir=contracts'`, which is correct from inside `contracts/`. Closed.
5. ADR 0012 header lists ADR 0019 as amending it, because the release artifact also carries `ledger.events.v1`. The ADR index states that Proposed ADRs may be edited until they are accepted.
6. Stale instructions in `.claude/agents/` were corrected for `architect`, `test-engineer`, `domain-dev` and `api-dev` (Oracle, long money, `JdbcClient`, int64, transcoding, wrong proto path). Every remaining `ledger-persistence-oracle` mention is labelled PENDING RENAME.
7. ADR 0022 has a new open item: where the Account Service API is defined (`BatchGetAccounts`, `ProvisionAccounts`, `AccountStateChanged`) and who generates its stubs for `ledger-account-client`.
8. Review §7.3 DDL changed for the outbox delivery rule (ADR 0024 D7) and was re-run on PostgreSQL 18.7 (image digest `sha256:b9f6b8ff…`) on 2026-11-15: 32 tables, 68 non-internal triggers, 86 policies, 69 indexes. The 35 earlier smoke checks still pass. 15 new outbox checks behave as expected: the relay cannot DELETE or change the payload, `published_at` cannot be reset, `ledger_app` cannot delete outbox or inbox rows, `ledger_cleanup` deletes only the 20-day-old published outbox and inbox rows and keeps the 1-day-old ones, and the owner's TRUNCATE is refused. Not run: concurrency, load, Flyway and jOOQ. The Flyway migration is not written.
9. Relay failure choice DECIDED (ADR 0024 D3.2, D7.3): a failing publish blocks its ordering key only. The relay retries with capped backoff; no row is skipped, reordered, parked or deleted automatically. Ordering is per `message_key` (`outbox_events.message_key`), not across keys (new invariant I-40 in `docs/domain/invariants.md`). Retention proposals with owners (review section 20.1): Kafka topic at least 7 days (Platform), outbox published rows 14 days (Ledger), consumer inbox 30 days (each consumer team). Chain (proposal): Kafka <= outbox <= inbox, 7 <= 14 <= 30 days; the inbox must exceed the outbox by at least the maximum consumer lag. Replay after outbox retention is operational recovery and reporting rebuild, not audit evidence (ADR 0024 D1, D7). Archival for legal retention is owned by compliance and reporting, with legal and DPO; cleanup only bounds the delivery tables (ADR 0024 D7). Open decisions: a visible, audited 'dead' state for a blocked key (CLOSED: quarantine is used instead, item 10); the partial index is a performance follow-up only (item 11). The DDL was changed later in this pass (item 10).
10. Final ADR 0024 decisions and DDL re-test. DECIDED, ordering: per aggregate, key `message_key` = `{tenant_id}:{aggregate_id}` (CHECK in DB); aggregates are transaction, wallet, bucket, hold, settlement batch and reconciliation run; TransactionPosted is not keyed by wallet because postings do not take the wallet lock; no global order (I-40). DECIDED, aggregate sequence required in v1 (I-41): sources are the history `event_seq` columns (`wallet_status_events`, `bucket_status_events`, `hold_events`, `settlement_batch_events`), `recon_runs.event_seq` (new column) and a deterministic sequence for transactions (corrected in item 11). DECIDED, quarantine replaces break-glass removal and the dead state: unpublished events are never deleted; a bypass is an append-only `outbox_quarantine` record inserted by `ledger_repair` (I-42). DECIDED, retention notation: Kafka <= outbox <= inbox, 7 <= 14 <= 30 days (proposals). DDL changed and re-tested on PostgreSQL 18.7 on 2026-11-15: review §7.3 now equals the tested schema apart from comments and blank lines; 33 tables, 71 non-internal triggers, 86 policies, 72 indexes; the 35 original smoke checks pass and 39 further checks behave as expected (15 retention, 24 new). TRUNCATE of `outbox_events` is refused by PostgreSQL itself because `outbox_quarantine` references it; the trigger stays as a second layer. Not run: concurrency, load, Flyway, jOOQ. Open (updated in item 11): settlement OPEN event (decided: `SettlementOpened`, sequence 1); `event_sequence` in read responses (contract follow-up); reason codes (decided, item 11) and reuse of `ledger_repair` for quarantine (still proposed); payload fields of events. Invariants were I-1 to I-42 at item 10; item 11 adds I-43 (grep-checked; the node link check was not re-run).
11. Final ADR 0024 decisions on sequences, settlement, metadata and the index, tested on PostgreSQL 18.7 on 2026-11-15. DECIDED, transaction sequence: a deterministic aggregate sequence (`TransactionPosted` 1, `FxExecuted` 2 for FX only, `TransactionReversed` next: 2, or 3 for an FX original); no stored counter, because `ledger_transactions` is append-only with no concurrent writers; the unique key and `ux_ltx_single_reversal` block duplicates. A missing lower event is detectable when a higher one arrives; the last event of a transaction is not detectable from this stream alone. Reversals are also covered by the reversal's own `TransactionPosted` (type REVERSAL, gapless `position_seq`) and by `reversed_by_transaction_id` on reads. DECIDED, settlement: new event `SettlementOpened` (sequence 1, written with the history row NULL to OPEN when the batch is created); the limitation 'first settlement event has sequence 2' is removed. DECIDED, operational metadata (I-43): controlled `reason_code` set; `reason_text` optional, printable ASCII, 1 to 300 characters, with no payload, financial data, customer identifiers, secrets or raw exception text; `last_error` is an error class name `[A-Za-z0-9_.:-]{1,200}`; `approval_ref` and `operator_id` are identifiers (printable ASCII, no space, at most 128); the DB checks the form, the content rule is procedure (approver and operator, grant review); alerts and logs never carry `reason_text`; readable by operations and compliance through `ledger_readonly`. DECIDED, partial index: not added; a performance follow-up only, to benchmark head-per-key selection, with `(message_key, outbox_id) WHERE published_at IS NULL` added only if measurements justify it. DDL delta (4 lines): CHECKs on `last_error` (two places), `reason_text` optional with length and ASCII checks, `approval_ref` and `operator_id` identifier checks. Counts unchanged: 33 tables, 71 non-internal triggers, 86 policies, 72 indexes. The original 35 smoke checks pass; 50 further checks behave as expected (15 retention, 35 for ordering key, aggregate sequence, quarantine and metadata rules). Finding: PostgreSQL's regex engine caps repetition counts at 255, so the 300-character limit is a separate `length()` check. Not run: concurrency, load, Flyway, jOOQ. Open: `event_sequence` in read responses (contract follow-up); reuse of `ledger_repair` for quarantine (still proposed); event payload fields; the performance follow-up above. Invariants are now I-1 to I-43.
12. Event envelope, transaction event writers and retention approvers (ADR 0024 D2, D2.8, D7.7), with I-44 and checklist B11 and B12. DECIDED, envelope v1 (minimum, immutable): one protobuf message `EventEnvelope` in `ledger.events.v1`, stored serialized in `outbox_events.payload` and sent as the Kafka record value (key = `message_key`). Required: `event_id`, `event_type`, `schema_version`, `tenant_id`, `aggregate_type`, `aggregate_id`, `aggregate_sequence`, `occurred_at` (UTC microseconds, set by the producing transaction), `correlation_id` (never empty), `producer`, `payload` (bytes). Optional: `causation_id`, `trace_parent`. Set once at insert; only the relay changes the four delivery columns; no PII, secrets, amounts or statuses in the envelope. The business payloads are NOT defined yet (open). DECIDED, transaction event writers: only the Ledger write path creates Transaction events, in the same DB transaction as the journal row; `ReverseTransaction` locks the original's positions in global order and checks 'already reversed' under those locks, so concurrent reversals serialise; `ledger_app` is the only role that inserts outbox rows. Final DB protection: the permanent partial unique index `ux_ltx_single_reversal` and the CHECKs tying REVERSAL to `reverses_transaction_id`. The outbox unique key on (tenant, aggregate_type, aggregate_id, aggregate_sequence) protects only while the rows exist, because cleanup removes published rows after `outbox_retention`; it is not the permanent guard. The DB does not check that the number is 2 or 3; a property test and a reconstruction test cover that. No new event store. DECIDED, retention approvers (7/14/30 proposals accepted with the ADR by the owner): every retention change needs architecture approval (the owner of the ADR set or whoever the owner designates, which confirms the chain; no other body is assumed); Kafka retention also needs Platform/Kafka operations approval; outbox retention also needs operations (storage) and compliance/reporting consulted; each inbox retention is set by the team running that consumer, never below outbox retention plus that consumer's max lag; changes are recorded in the configuration repository or runbook; a new ADR is needed only if the ordering rule itself changes. NO DDL change in this round. Open: business payload definitions; `event_sequence` in read responses (contract follow-up); reuse of `ledger_repair` for quarantine (still proposed); the partial-index performance follow-up (benchmark first). Invariants are now I-1 to I-44.
13. Final decisions on ADR 0024 (domain rule, producer enum, governance) and the acceptance order. DECIDED, I-41 Transaction sequence is a domain invariant: TransactionPosted(T) = 1; FxExecuted(T) = 2 only if T is FX; TransactionReversed(T) = 2 if T is not FX, 3 if T is FX; each event at most once. Database constraints alone do not enforce the values: unique keys stop duplicates, the outbox key protects only while the rows exist, and `ux_ltx_single_reversal` stops a second reversal. The rule is enforced in `ledger-core` as a pure function of transaction type and event type, covered by a property test, and re-checked by the reconstruction test (D7.8). DECIDED, `producer` in the envelope is a closed enum `Producer` (v1: only `PRODUCER_LEDGER`; `PRODUCER_UNSPECIFIED` is invalid; no host, instance or version; values are added only by an ADR change). It is not an authorisation claim; topic ACLs decide who may produce to `ledger.*`. DECIDED, governance: any retention change needs architecture approval (the owner of the ADR set or whoever the owner designates; no other body is assumed), which confirms `Kafka retention <= Outbox retention <= Inbox retention`. This replaces the earlier wording that made the Ledger owner the checker of the ordering rule. ADR 0024 has a 'Dependencies and acceptance order' section; its open items are grouped as owner acceptance with the ADR, outside the ADR (archival), and remaining implementation decisions. Recommended acceptance order: (1) ADR 0014, 0015, 0016, 0017; (2) ADR 0021, 0022, 0023, 0025 together with 0019 and 0026; (3) ADR 0024; or all in one decision. Never accept 0024 while 0014 or 0017 is still Proposed. Remaining implementation decisions, grouped: payload schemas (business payloads per event type); Kafka configuration (replication, min.insync.replicas, topic retention values); relay implementation (leader lock, head-per-key selection, backoff cap, the partial-index performance follow-up); consumer contracts (resync wait bound, dedupe memory, DLQ). No ADR is accepted in this pass; all remain Proposed. Invariants are I-1 to I-44.

Verification results:

- `./gradlew spotlessCheck compileJava compileTestJava` passed.
- `./gradlew test` passed: `ArchitectureTest` 4 tests, `HealthEndpointTest` 1 test, 0 failures, about 6 s. `ArchitectureTest` still uses `allowEmptyShould`, so it proves little. The 35-minute hang did not recur. The two `java.exe` processes seen earlier were SonarLint JREs from VS Code, not Gradle.
- A node script checked 47 markdown files: no broken relative links; every "ADR NNNN Dn" reference resolves to a decision heading; invariants I-1 to I-38 were all defined at that check (I-39 and I-40 were added later; the node check was not re-run) and every I-n reference resolves; statuses as above; no old draft numbers 0027 to 0043 used as live references.
- buf 1.75.0, installed from npm into a scratch folder outside the repo: `buf lint` passed; `buf build '../.git#branch=main,subdir=contracts'` accepted the documented git input; `buf breaking` between image files behaved correctly (unchanged tree against `main`: exit 0; renamed enum value: exit 100). The literal `buf breaking --against '../.git#branch=main,subdir=contracts'` (and `--against .`) exited 1 with no message on this Windows machine. It is not verified end to end; a Linux run is needed. `buf format -d` shows whole-file diffs only because the Windows working copy has CRLF and git stores LF (`core.autocrlf=true`).

Not run: gitleaks, markdownlint, an anchor link checker, Testcontainers and PostgreSQL integration tests (no persistence code yet), Flyway, jOOQ, Kafka, concurrency and load tests, and buf on Linux.

Decisions and open points for the owner. None is decided here.

- (a) Split ADR 0021 (216 lines)? Recommendation: not yet. D4 and D5 are cited from many files, so a split now needs a scripted renumber. A grep finds 32 references to ADR 0021 D-numbers across `docs/`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md` and `.claude/`; the count of about 40 for D4 and D5 alone was not confirmed. If the owner splits it, move D4 and D5 (lifecycle, matrix, origin authorization) into a new ADR. D6 alone (payment timeline) is the cheap, low-risk cut. **Decided: ADR 0021 kept as one ADR, rationale in ADR 0021 Alternatives rejected.**
- (b) Reversing a `HOLD_CAPTURE` transaction restores balances but does not reinstate the hold. No ADR says so.
- (c) Location of the Account Service contract (open item in ADR 0022).
- (d) The `ledger.events.v1` protos do not exist yet. ADR 0024 D2 names the path.
- (e) ADR 0024 D3 says published outbox rows are deleted by `ledger_relay` but not when. ADR 0014 D3 grants the relay `SELECT`, `DELETE` and `UPDATE` of three columns. **Decided in ADR 0024 D7 (proposal): separate cleanup job and role `ledger_cleanup`, retention chain, never delete unpublished rows, no archival in v1.**
- (f) Document dates 2026-11-15 versus commit dates in March 2026, as before.
- (g) Persian review text not proofread.
- (h) The 8 owner-open items in review section 20.1 are unchanged.
- (i) Whether to keep the ADR 0026 D2 change (item 3 above).

## 5. Sarv findings

Sarv is the owner's existing .NET modular monolith (SarvNeoBank). It is a separate repo, not in this repo. The review reads it read-only.

Spot-checked in this pass (the rest is from the review and was not re-checked):

- `Currency` enum exists in the Ledger domain. The value list (only `IRR`) was not checked.
- `ExecuteUpdate` is used in the Ledger infrastructure layer.
- `RowVersion` exists on `BalanceBucket` and `Reservation`.

Findings from the review (section 3 and 10 of the review):

| Area | Finding (as reported) |
|---|---|
| Currency | One currency only. No FX. |
| Idempotency | Unique on `(ReferenceId, OperationType)`. No payload comparison. |
| Transactions | Mutable rows. |
| Ledger entries | Cascade delete. No database-level immutability found. |
| System account | Balance updated by a merged `balance = balance + delta` statement. |
| Tenancy | No tenant concept. |
| Reservations | Full capture only. A locked balance blocks all other debits. |
| Audit | Detect-only job, windowed. |
| Payments and Ledger | Written in separate transactions. Uses `PaymentStatus.NeedsReconciliation`. |
| HTTP logging | Best-effort. Not a payment timeline. |
| Load test harness | The review names `tests/SarvNeoBank.Modules.Ledger.LoadTests` with scenarios `distributed`, `hot`, `fee`. No recorded results were found. |

## 6. Verified vs not verified

### 6.1 Checked against the repo in this handoff pass

| Claim | Result |
|---|---|
| Branch `docs/arch-decisions`, base `7604c2b` | Confirmed. |
| Remote `origin` and remote branches `main` and `chore/bootstrap` | Confirmed. |
| ADR files 0001 to 0032, with old 0014 to old 0032 `Proposed` | Confirmed at the time. 0001 to 0013 are `Accepted`. Since 2026-11-15 the files are 0001 to 0026 (consolidation). |
| 19 new ADR titles | Confirmed (section 4.2). |
| `Amended by` line on 0002, 0004 to 0011, 0013 | Confirmed. |
| Review structure: 21 sections, ownership table, DDL in section 7.3 | Confirmed. |
| 24 tables in section 7.3 | Confirmed. |
| 4 more tables in section 10 (conceptual Payments model): `payments`, `payment_status_transitions`, `provider_interactions`, `payload_access_log` | Confirmed. These are not in the DDL. |
| ADR verdict counts 6 / 9 / 4 / 0 | Confirmed in section 19.1. |
| Review section 2.2 run log (DDL, container, Gradle failure) | Present in the review. The runs were not repeated. |
| Project agents and their tools and models | Confirmed (section 3.1). |
| Global agents `committer`, `doc-writer`, `test-writer`, all haiku | Confirmed. |
| `.claude/settings.json` deny and ask rules | Confirmed. |
| ArchUnit rule uses `oracle..` | Confirmed in `ledger-server/src/test/java/com/kimialedger/server/ArchitectureTest.java`. |
| `CLAUDE.md` still names Oracle | Confirmed. |
| Review section A (agents) and drift list | Confirmed. |
| Working tree: 35 entries | Confirmed. |
| Git log dates: existing commits are dated 2026-03-22 | Confirmed. |

### 6.2 Not verified

| Claim | Why it is not verified |
|---|---|
| Throughput numbers and capacity targets | No measurements. None exist. |
| Oracle dead-session lock behavior | Secondary sources only. Oracle is being dropped, so low priority. |
| Closed-enum behavior in JSON clients | Secondary sources only. |
| Immutable-table details in Oracle | Secondary sources only. |
| jOOQ OSS support for PostgreSQL 18 | Licence note says free only for the latest PostgreSQL. Explicit PG 18 support not verified. |
| Version pins from the review (Spring Boot 4.1.2 BOM, jOOQ 3.21.9, pgJDBC 42.7.14, kafka-clients 4.2.2, spring-kafka 4.1.2, Flyway 12.4.0) | Taken from the review's web lookups. Not checked in this pass. |
| Concurrency, load, Flyway, jOOQ, Kafka behavior | Not run. |
| Cause of the stalled `:ledger-server:test` run | Did not recur on 2026-11-15: `ArchitectureTest` and `HealthEndpointTest` passed in about 6 s. The earlier hang is still unexplained. |
| Sarv load test path and results | Path in the review not found in a shallow listing of Sarv's `tests/`. No results found. |
| Sarv claims other than the three spot checks | Not re-checked in this pass. |
| Fable design audit findings R-1 to R-20 | The report is not in the repo. Cannot be checked from the repo. |
| Owner's Java and .NET team skills | The repos hold no evidence. |
| Module skeleton and `/health` endpoint | Named in the session notes. Not checked in this pass. |
| `buf breaking --against '../.git#branch=main,subdir=contracts'` (the literal documented command) | Exited 1 with no message on Windows, and with `--against .`. Not verified end to end. Needs a Linux run (Docker was not permitted). Comparisons between image files, `buf lint` and `buf build` did work. |
| `ArchitectureTest` as a guard | Uses `allowEmptyShould`, so an empty rule passes. It proves little until the rules are rewritten (section 9). |
| gitleaks, markdownlint, anchor link checks | Not run. A node script checked relative links and ADR references only. |
| Testcontainers, Flyway, jOOQ, Kafka, concurrency and load tests | Not run. No persistence code exists yet. |
| Re-run of the review DDL | Not re-run in the 2026-11-15 semantic pass. Last run on postgres:18.7 (section 4.6). |
| Persian text in the review and ADRs | Not proofread. |
| `buf format -d` output | Shows whole-file diffs because the Windows working copy has CRLF and git stores LF (`core.autocrlf=true`). Not a content problem. |

## 7. Next steps

Order of work, from the review (sections 20 and 21) and the decisions made in this session:

1. **Done 2026-11-15:** the owner answered items 1 to 4 in section 8, then gave final decisions on the rest (section 8).
2. **Done 2026-11-15, uncommitted:** ADRs old 0033 to old 0043 written as `Proposed`. `Superseded by` / `Amended by` lines, with "historical" labels, were added to the accepted ADRs; no status field was changed. `invariants.md`, `threat-model.md`, `checklist.md`, the review, `CLAUDE.md`, `README.md` and `CONTRIBUTING.md` were aligned. The DDL in review section 7.3 was rewritten and re-run.
   - **Done 2026-11-15, uncommitted:** consolidation pass. The 30 Proposed drafts (old 0014 to old 0043) were merged into 13 ADRs, 0014 to 0026, all `Proposed`; superseded and rejected drafts are recorded under "Alternatives rejected" in the ADR that replaced them. The wallet status model, exceptional-operation authorization, provisioning saga and payment-timeline boundary are in 0021, the overdraft policy in 0022, FX valuation and reporting currency in 0025, durability targets in 0014, the capacity method in 0017. Old-to-new map: `docs/adr/README.md`. All cross-references were re-pointed.
   - Next: the owner reads and accepts, revises or rejects each of 0014 to 0026.
   - Then: on acceptance, set the status of 0002 to `Superseded by 0014` (README convention). The superseded drafts (old 0019, old 0020, old 0021, old 0022, old 0028) no longer exist as files.
   - Then: protos (list in review section 13.1), `ArchitectureTest`, Gradle module rename, jOOQ version pins (section 9). `.claude/agents/`, `docs/integration.md` and `contracts/README.md` were edited in the 2026-11-15 semantic pass (section 4.7); the owner checks them.
   - Still open: the parts that came from old 0015, old 0023, old 0026, old 0030, old 0031 and old 0032 (now in ADRs 0019, 0020, 0017 and 0026) needed small fixes before acceptance; the consolidation applied the PostgreSQL updates named in review section 19.1. The owner checks them when reading.
3. **Vertical spike** (review section 21, item 3). Build `PostTransaction` end to end on PostgreSQL 18, with the DDL from section 7.3, Flyway, the sorted-lock write path, the I-4 trigger, RLS, concurrency tests (duplicate key, A to B, simultaneous reversal), and a simple relay to Kafka. Fix capacity numbers only after this spike.
4. Then holds, account fence, FX and settlement, in that order. Then shadow migration from Sarv (review section 18, phase 1). Until then Sarv stays the reference system.
5. **Investigate the stalled `:ledger-server:test`** run. It did not recur on 2026-11-15 (section 4.7). Find whether it was a real hang; if it recurs, record the time and the running processes.
6. **Resolve the overlapping agent sets** (section 3.3). Owner choice.
7. **Usage fix, not applied.** Two changes were proposed and are waiting for the owner's answer:
   - Change A: add an `env` block to `~/.claude/settings.json` with `"CLAUDE_CODE_SUBAGENT_MODEL": "sonnet"`. The file has no `env` block today.
   - Change B: append a rule to `~/.claude/CLAUDE.md`. Do not pass model or effort on agent calls unless the owner asks in that turn. Announce long tasks.
   - Background: `general-purpose` is a built-in subagent with no editable file. Per the Claude Code docs, a per-call model overrides frontmatter, then the env var, then the main model. The heavy usage came from two `general-purpose` runs on the Fable model at extra-high effort, which the owner asked for. One of them hit the 5-hour limit.
   - Not recommended: `CLAUDE_CODE_SUBAGENT_MODEL_FORCE=1`.
8. **Commit plan** (owner asked for it; partly done). Seven commits exist on the branch (section 1.1). Remaining chunks, per `CLAUDE.md`:
   - `docs(adr)`: ADRs 0014 to 0026 (consolidated; the old draft files are deleted in the working tree), the amendment lines on 0002 to 0013 and `docs/adr/README.md`.
   - `docs(domain)` and `docs(security)`: `invariants.md`, `threat-model.md`, `checklist.md`. First commits are `6665efe` and `b990ab8`; later edits are uncommitted.
   - `docs(architecture)`: the review. First commit `b5b95ff`; later edits are uncommitted.
   - `docs(handoff)`: this file. First commit `cc9ff32`; later edits are uncommitted.
   - `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, `contracts/README.md`, `docs/integration.md`: aligned on 2026-11-15, uncommitted.
   - `.claude/agents/`: corrected on 2026-11-15, uncommitted.
   - `CHANGELOG.md`: in the chunk that matches the change.
   - Owner asked that commit dates be 24 March. The year and meaning are still being confirmed with the owner. Existing commits are dated 2026-03-22.
   - Push is blocked by `.claude/settings.json`. The owner must push by hand, or lift the deny rule.
   - Committing needs the `committer` subagent per the global rules.
9. **Send the Appendix A prompt to GPT** to get the owner's intended structure. Then run one independent read-only review of that output with an Opus agent at high effort, using the owner's eight-section format. The full list of section names is not in the repo, so ask the owner for it. The known names are "Overall verdict" (first) and "Final recommendation" (last).
10. **Order after the 2026-11-15 semantic pass** (section 4.7): owner review of the diff; then commit in logical chunks (item 8); the owner pushes; then the follow-ups: rewrite the protos with buf installed, rename the Gradle module and the `ArchitectureTest` rules, pin versions, add the CI job (including the Linux `buf breaking` run), and accept the Proposed ADRs one by one.

## 8. Open owner decisions

Items 1 to 4 were answered on 2026-11-15; the owner then gave final decisions on the rest the same day. The ADRs stay `Proposed` until the owner accepts their text. Only the eight items listed under the table stay open, plus the owner decisions from the 2026-11-15 semantic pass (section 4.7).

| # | Decision | Options | Agent recommendation |
|---|---|---|---|
| 1 | Is Oracle still a deployment requirement? | Yes or no | **Answered 2026-11-15: no.** PostgreSQL 18 is the baseline. old 0028 rejected, ADR 0002 superseded. See ADR 0014. |
| 2 | Bucket to Account mapping | (a) 1:1. (b) N:1 per customer. (c) Pooled omnibus. | **Answered 2026-11-15: (a) 1:1 for customer balances.** Pooling of customer balances rejected; pooling only for system accounts (fee, clearing, settlement, FX position). The review had recommended (b). See ADR 0021. |
| 3 | Asset list and scale | `bigint` or `NUMERIC(38,0)` | **Answered 2026-11-15: `NUMERIC(38,0)` / `BigInteger`.** Fiat, crypto, token and stock, scale up to 18. Wire amounts are decimal strings. The concrete asset list is still open. See ADR 0015. |
| 4 | Contract with the Account Service | Events, `account_version`, synchronous freeze fence, close seal | **Answered 2026-11-15:** `AccountStateChanged` events, cache and snapshots by `account_version`, `BatchGetAccounts` before the transaction, freeze fence `ApplyAccountState`, close seal under Ledger locks, no 2PC. The residual race is owned by the Account Service. See ADR 0022 (also 0021). |
| 5 | Wallet rules | Wallets per owner, suspended credits, overdraft, product catalog, who supplies `external_account_id` | **Answered 2026-11-15:** several wallets per owner (limits are business policy); states `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `FROZEN`, `CLOSING`, `CLOSED`, with system-originated credits allowed while `SUSPENDED`; overdraft per Account Service policy, default 0; catalog is configuration; the Account Service supplies account ids through the provisioning saga; one customer account may back several assets, one bucket each. See ADRs 0021, 0022. |
| 6 | Java or .NET | A, B or C | **Answered 2026-11-15: C**, independent Java service. See ADR 0014. |
| 7 | Payment timeline | Owner, retention, DPO | **Boundary decided** (ADR 0021). Retention and DPO sign-off stay open. |
| 8 | RPO, RTO, retention, TPS, latency | Numbers | **Answered 2026-11-15:** RPO 0 inside the synchronous HA domain, RTO 15 min as a target (ADR 0014). Cross-region DR, TPS and latency, and legal retention stay open. |
| 9 | Hold policies | Over-capture, capture after freeze, expiry | **Answered 2026-11-15:** over-capture rejected; capture while `SUSPENDED` only by policy, never while `FROZEN`; release always; TTL per product policy (ADR 0023). Exact TTL values stay open. |
| 10 | FX policies | Reporting currency, rate source, single-use quote | **Answered 2026-11-15:** single-use quotes, execution versus valuation rates, reversal at the original rate (ADR 0025). Reporting currency and rate sources stay open. |
| 11 | jOOQ or plain `JdbcClient` | | **Answered 2026-11-15: jOOQ** for v1 (ADR 0014); the licence risk is kept with a CI check. |
| 12 | Posting rights per wallet; activity topic; schema registry | | Posting rights: accepted residual risk. No activity topic and no schema registry in v1 (ADR 0024, proposed). |
| 13 | Oracle JDBC driver licence exception | | Moot: Oracle dropped. |
| 14 | Minimum Oracle version and edition | | Moot: Oracle dropped. |
| 15 | Certificate-bound tokens as default | | Required by default per ADR 0020 (Proposed); accepted with that ADR. |
| 16 | REST in v1, or gRPC only | | REST and gRPC per the owner baseline and ADR 0019 (Proposed). |
| 17 | Back-dating limit | Default 366 days | Default in ADR 0016 (Proposed); accepted with that ADR. |

Still open (review section 20.1, with assumptions and decision owners): cross-region DR RPO/RTO; numeric TPS and latency targets; exact hold TTL values; functional/reporting currency per tenant; approved FX rate sources and valuation policy; legal retention and raw payment payload retention; exact Account Service freshness SLO; team staffing and on-call coverage.

## 9. Required repo changes not yet made

Source: review section 19.2, updated 2026-11-15.

| File or area | Change | State |
|---|---|---|
| `CLAUDE.md`, `README.md`, `CONTRIBUTING.md` | PostgreSQL 18, jOOQ in persistence only, modules, ownership table, money rule, scopes, write path, image | Done 2026-11-15, uncommitted. Re-aligned in the semantic pass (section 4.7). |
| `ArchitectureTest` (`ledger-server/src/test/java/com/kimialedger/server/ArchitectureTest.java`) | Replace `oracle..` with `org.postgresql..` and `org.jooq..` limited to `persistence.postgres..`; add a Kafka rule. This tightens the rules. | Not done (Java was out of scope). |
| Gradle build | Rename `ledger-persistence-oracle` to `ledger-persistence-postgres`; add `ledger-contracts`, `ledger-messaging-kafka`, `ledger-account-client`; pin jOOQ, pgJDBC, Flyway and the PostgreSQL image; CI job for the jOOQ and PostgreSQL pair | Not done. |
| `contracts/proto/ledger/v1` | Exact edit list in review section 13.1: amounts as strings, wallet, hold, FX, settlement and fence services, `Entry` to `Transaction`, `position_seq`, `account_version`, error codes, `ledger.events.v1` | Not done. The protos still carry the draft names, and `ledger.events.v1` does not exist yet. `buf lint` and `buf build` passed on 2026-11-15 with buf 1.75.0 outside the repo; nothing was regenerated. |
| Stale docs | `docs/integration.md` (used to describe `CreateAccount`, `accounts:*`, Oracle), `contracts/README.md` | Edited 2026-11-15, uncommitted. The integration guide now says there is no account API, and grep finds no Oracle mention. `contracts/README.md` was not re-checked in this handoff. |
| Agent files (`.claude/agents/`) | `architect` cannot write ADRs; `api-dev` has a wrong proto path; Oracle and outbox wording is stale | Corrected 2026-11-15 for `architect`, `test-engineer`, `domain-dev` and `api-dev` (section 4.7), uncommitted. Remaining `ledger-persistence-oracle` names are labelled PENDING RENAME. Not re-read in this handoff. |
| Review §7.3 DDL | Outbox delivery rule (ADR 0024 D7) and quarantine, aggregate sequence and `first_failed_at` (ADR 0024 D2, D7.12): roles `ledger_cleanup` and `ledger_repair` grants, guard triggers on `outbox_events` and `outbox_quarantine`, indexes `ix_outbox_published`, `ix_inbox_received`, relay grants without DELETE | DDL tested on postgres:18.7 on 2026-11-15 (section 4.7, items 8, 10 and 11). Flyway migration not written. |

Note: no ADR status was changed to Accepted or Superseded. That happens after the owner accepts the replacements.

## 10. Cost and model notes

| Work | Model and effort | Size |
|---|---|---|
| Design audit | Fable, extra-high | About 179k tokens, about 13 minutes |
| ADR drafting, ADRs old 0014 to old 0017 | Fable, extra-high. Cut off by the 5-hour rate limit. | Not measured |
| ADR drafting, ADRs old 0014 to old 0032 (completion) | Opus, high | About 212k tokens, about 18 minutes |
| Architecture review, including wallet ownership revision | Opus, high | About 451k tokens in total, about 59 minutes |

- The owner prefers Opus (high effort, extra-high only if needed) over Fable for subagents.
- An agent cannot change the main session's model.

## 11. Corrections

Mismatches between the session brief and the repo. Each one says what the repo shows.

- Mismatch: The brief says "section 7 (Next steps)" of the review. The review has no Next steps section. Section 7 is "Database Schema and DDL". Next steps are in sections 20 (Open Decisions and Risks) and 21 (Final Recommendation).
- Mismatch: The brief says the DDL has 24 tables. Section 7.3 has 24 tables. The whole review has 28 `CREATE TABLE` statements, because section 10 has 4 more in the conceptual Payments model. The DDL count is correct. The total in the document is 28.
- Note: The brief says the owner wants commits dated 24 March. Existing commits on this branch are dated 2026-03-22. This is a fact, not a repo mismatch. The year is still being confirmed with the owner.

## Appendix A

Prompt for GPT, in Persian, verbatim. Use it to get the owner's intended structure, then run a review on the result.

```text
تو معمار ارشد یک سرویس لجر دابل‌انتری هستی. من می‌خواهم ساختار پیشنهادی را به‌صورت یک سند کامل و بدون حدس به من بدهی تا بدهم یک reviewer مستقل نقدش کند.

قواعد:
- هر چیزی که هنوز تصمیم‌گیری نشده را صریحاً بنویس «تصمیم‌گیری نشده» و حدس نزن. اگر برای یک بخش گزینه‌های مختلف داری، همه را با مزایا و معایب بنویس و یکی را «پیشنهاد» علامت بزن.
- هر ادعای فنی درباره‌ی PostgreSQL، Kafka، Spring یا Hibernate را اگر مطمئن نیستی با «تأیید نشده» علامت بزن.
- بی‌رحم و دقیق باش. فقط تعریف نکن.

سند را با این بخش‌ها بنویس:

1. هدف و محدوده: ledger چه چیزی را حل می‌کند و چه چیزی را عمداً حل نمی‌کند (مثلاً hold، FX، تسویه).
2. اهداف مقیاس: تعداد entry در ثانیه (میانگین و پیک)، حجم داده و retention، تعداد tenant، وجود حساب‌های hot (مثل حساب کارمزد که تقریباً همه‌ی entryها به آن می‌خورند). اگر عدد نداری بنویس «اندازه‌گیری نشده» و یک بازه‌ی منطقی همراه فرض‌هایش بده.
3. الزامات دوام و دسترس‌پذیری: RPO و RTO، تعداد replica و region، و اینکه failover باید acknowledged writeها را حفظ کند یا نه.
4. مدل داده: DDL کامل جدول‌ها (accounts، entries، postings، balances یا مشابه، idempotency، outbox، tenant registry) با کلیدها، index ها و constraintها.
5. مدل balance: ذخیره‌شده یا محاسبه‌شده؟ چگونه از journal بازسازی می‌شود و چه کسی آن را اجرا می‌کند؟ خواننده‌ها چه freshness لازم دارند؟
6. Write path قدم‌به‌قدم: قفل‌گذاری و ترتیبش، validation، insert، به‌روزرسانی balance، outbox، و commit. timeoutها و رفتار هنگام contention.
7. Idempotency: scope کلید، رفتار با درخواست rejected، تعریف hash payload، retention.
8. Reversal: قواعد reversal یک reversal، حساب بسته‌شده، کمبود موجودی.
9. Multi-currency: FX داخل ledger است یا بیرون؟ rounding با چه کسی است؟ exponent هر ارز از کجا می‌آید؟
10. API: فهرست RPC/endpoint ها، فیلدهای هر کدام، pagination، کدهای خطا و نگاشت status. REST و gRPC چگونه با هم هماهنگ می‌مانند؟
11. Event-driven: مصرف‌کننده‌ها دقیقاً چه کسانی‌اند؟ ordering و delivery guarantee لازم چیست؟ اگر مصرف‌کننده‌ی مشخصی نیست، صریح بنویس تا بشود Kafka/Redpanda و CQRS را رد یا توجیه کرد.
12. Auth و tenancy: مدل tenant، claim ها، scope ها، و اینکه چه کسی می‌تواند حساب overdraftable یا system بسازد.
13. دسترسی دیتابیس: نقش‌ها و privilegeهای هر کدام روی هر جدول (runtime، migration، relay). چگونه append-only بودن در سطح DB اعمال می‌شود، با توجه به اینکه balance یا وضعیت حساب باید تغییر کند.
14. Persistence: JPA/Hibernate در برابر JDBC با دلیل مشخص برای ledger.
15. Operability: metrics، alerting، reconciliation، backup، migration روی جدول‌های بزرگ، و چه کسی روی on-call است.
16. محدودیت‌های سازمانی: اندازه‌ی تیم، تجربه، compliance و الزامات audit.
17. تصمیم‌های باز: فهرست مرتب‌شده‌ی چیزهایی که فقط من می‌توانم تصمیم بگیرم، با گزینه‌ها و پیشنهادت.

خروجی را به‌صورت یک فایل Markdown بده که بتوانم مستقیم در ریپو بگذارم.
```

Note (English): This prompt predates the owner's Wallet and Account ownership addendum. If it is used again, add this line to the prompt: "Ledger owns Wallets and BalanceBuckets; Accounts are external".
