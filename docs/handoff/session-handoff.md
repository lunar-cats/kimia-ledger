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
| Uncommitted files | 35 entries in `git status` (15 modified, 19 new ADRs, 1 new folder `docs/architecture/`) |

Before you start on a new machine:

1. Clone the repo and check out `docs/arch-decisions`.
2. Check whether the branch was pushed. Everything in section 4.1 is uncommitted, and this file is too. If the work was never pushed, it exists only on the old machine. Copy the working tree from there first.
3. Run `git status` and confirm the 35 entries above.

### 1.2 First message for a new Claude Code session

Use this message. It points to the sections that exist in the review file.

> Read docs/handoff/session-handoff.md, then docs/architecture/ledger-architecture-review.md sections 20 and 21, and continue with the next steps in section 7 of this handoff.

Note: the first draft of this message said "section 7 (Next steps)" of the review. The review has no such section. Section 7 of the review is the database schema. Next steps are in sections 20 and 21 of the review, and in section 7 of this handoff. See Section 11 (Corrections).

### 1.3 Two rule files that a new machine needs

These files are separate. The global file is not in the repo, so it does not travel with a clone.

| File | Where | What it holds |
|---|---|---|
| `CLAUDE.md` | repo root | Project rules for agents. Still says Oracle. A rewrite is required (section 9). |
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

Kimia Ledger is an open-source (Apache 2.0) double-entry ledger service. Stack in the repo: Java 25, Spring Boot, Gradle (Kotlin DSL), Oracle (current `CLAUDE.md`).

### 2.1 State of the code

- Pre-alpha.
- The repo has the module skeleton, ArchUnit tests, and a `/health` endpoint.
- There is no domain code yet.

### 2.2 Repo rules (`CLAUDE.md`)

| Rule | Detail |
|---|---|
| Module layout | Hexagonal. `ledger-core` depends on nothing. `ledger-application` depends on core. Persistence and API adapters depend on application and core. `ledger-server` wires everything. |
| Money | Integer minor units plus ISO-4217 currency. No `float` or `double`. |
| Journal | Append-only. Corrections are reversing entries. |
| Write path | One database transaction. Lock accounts in sorted order with `SELECT ... FOR UPDATE`. Validate, insert postings, update balances, insert outbox row. |
| Idempotency | Required on every write. Unique per `(tenant_id, idempotency_key)`. Same payload returns the original result. Different payload returns `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`. |
| Tenancy | `tenant_id` on every table and query. |
| PII | None in the ledger. Only opaque `external_ref`. |
| Commits | Conventional Commits. One logical concern per commit. **No attribution trailers.** |
| ADRs | `docs/adr/NNNN-title.md`, with context, decision, consequences. |
| CHANGELOG | Keep-a-Changelog format. Update in the same change. |

Forbidden (from `CLAUDE.md`):

- Push to any remote.
- Commit to `main`.
- Hard resets.
- Edit `.github/workflows/**` without asking.
- Add a dependency without a license check.
- Use floating-point money.
- Update or delete journal rows.
- Put SQL outside the persistence module.
- Import framework code into `ledger-core` or `ledger-application`.
- Loosen an ArchUnit rule, a `buf breaking` check, or a security test.

### 2.3 Local permissions (`.claude/settings.json`, checked in)

| Setting | Effect |
|---|---|
| `deny`: `Bash(git push:*)`, `Bash(git push --force:*)`, `Bash(git reset --hard:*)`, `rm -rf`, `rm -fr`, pipes from `curl` or `wget` into a shell | Blocked. |
| `ask`: `Edit`/`Write` on `.github/workflows/**`, `Bash(git commit:*)` | Asks before running. |

Result: the owner must push by hand, or lift the `git push` deny rule.

## 3. Agents

### 3.1 Project agents (`.claude/agents/`)

| Agent | Model | Tools | Notes |
|---|---|---|---|
| `architect` | inherit | Read, Grep, Glob | Description says it writes ADRs. It cannot write, because it has no Edit or Write tool. |
| `domain-dev` | inherit | Read, Grep, Glob, Edit, Write, Bash | Owns core, application, and the Oracle persistence module. |
| `api-dev` | sonnet | Read, Grep, Glob, Edit, Write, Bash | Owns `contracts/` and the REST and gRPC adapters. |
| `test-engineer` | sonnet | Read, Grep, Glob, Edit, Write, Bash | Writes all tests. |
| `security-reviewer` | inherit | Read, Grep, Glob, Bash | Read-only by instruction. |
| `docs-writer` | haiku | Read, Grep, Glob, Edit, Write | Writes markdown. |
| `git-committer` | haiku | Bash, Read, Grep, Glob | Git only. |

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
| `docs/architecture/ledger-architecture-review.md` | Final review in Persian. 21 sections, about 1,930 lines. Ownership, Wallet/Bucket/Account mapping, DDL, flows, Kafka, REST/gRPC, ADR verdicts, open decisions, recommendation. | New. Uncommitted. Proposal only. No ADR was set to Accepted. |
| `docs/adr/0014-per-account-posting-sequence.md` to `docs/adr/0032-contract-freeze-and-breaking-baseline.md` | 19 new ADRs (see 4.2) | New. All `Proposed`. Uncommitted. |
| `docs/adr/0002`, `0004`, `0005`, `0006`, `0007`, `0008`, `0009`, `0010`, `0011`, `0013` | Each has an `Amended by` line. | Modified. Uncommitted. Status not changed. |
| `docs/adr/README.md` | Index updated for the new ADRs | Modified. Uncommitted. |
| `docs/domain/invariants.md` | I-18 is now per account. I-22 to I-30 added. | Modified. Uncommitted. |
| `docs/security/threat-model.md`, `docs/security/checklist.md` | Updated for the review | Modified. Uncommitted. |
| `CHANGELOG.md` | Updated | Modified. Uncommitted. |
| `docs/review/2026-11-14-architecture-review.md` | Earlier review (findings B, D, P, A, T, S). | Already tracked, committed in `acd881f`. Not changed in this session. |
| `docs/handoff/session-handoff.md` | This file | New. Uncommitted. |

### 4.2 ADR titles 0014 to 0032

| ADR | Title (from the file) | Amends (from the file header) |
|---|---|---|
| 0014 | Per-account posting sequence; no tenant-wide journal order | none |
| 0015 | REST is a hand-written adapter that conforms to the gRPC-JSON transcoding mapping | 0004 |
| 0016 | Entry model: effective time, server-built reversals, repeated accounts, input message | 0005, 0007 |
| 0017 | Idempotency semantics: one key, one outcome | 0009 |
| 0018 | Error contract, status mapping and correlation id | 0004 |
| 0019 | Money range, storage width, currency column and exponent | 0002, 0006 |
| 0020 | Write path with insert-only running balances | 0005, 0007, 0008, 0013 |
| 0021 | No events and no outbox in v1 | 0008, 0013 |
| 0022 | No native holds in v1; holds are modelled with a hold account | none |
| 0023 | Service edge: tenant identity, TLS termination, rate limiting | 0010, 0013 |
| 0024 | A separate scope to create accounts that may go negative | 0013 |
| 0025 | Account lifecycle, lookup and caller-supplied fields | 0011 |
| 0026 | Timeouts, per-tenant concurrency cap and hot accounts | none |
| 0027 | Tenant isolation in the database: composite keys now, row policies later | 0010 |
| 0028 | Oracle only in v1; driver licence exception; immutable tables before production | 0002 |
| 0029 | Reconciliation, account freeze, recovery and retention | 0007 |
| 0030 | Transaction port in ledger-application | none |
| 0031 | ledger-contracts module for generated Java and the proto mapping | none |
| 0032 | Contract freeze, breaking-change baseline and client tolerance | 0004 |

All 19 show `Status: Proposed`, dated 2026-11-14.

### 4.3 Review verdicts on the 19 ADRs (review section 19.1)

| Verdict | Count | ADRs |
|---|---|---|
| ACCEPT | 6 | 0015, 0023, 0026, 0030, 0031, 0032 |
| REVISE | 9 | 0014, 0016, 0017, 0018, 0019, 0024, 0025, 0027, 0029 |
| REJECT | 4 | 0020, 0021, 0022, 0028 |
| DEFER | 0 | none |

ADR 0025 is split. Wallet and bucket lifecycle stays in Ledger. Account lifecycle moves to the Account Service.

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
| `./gradlew test --offline` | Failed at configuration. The Spotless plugin was not in the local cache. Not a code problem. |
| `./gradlew test` (online, background) | Dependencies downloaded. All modules compiled. `:ledger-server:test` produced no output for about 35 minutes and was stopped. Cause unknown. |
| `buf lint`, `buf breaking` | Not run. `buf` is not installed. |
| Concurrency, load, Flyway, jOOQ, Kafka tests | Not run. |

Machine notes:

- JDK installed: 26.0.1. The build uses a Java 25 toolchain.
- Two `java.exe` processes started 14:48 and 14:49 may be leftover Gradle daemons. They were not killed.

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
| ADR files 0001 to 0032, with 0014 to 0032 `Proposed` | Confirmed. 0001 to 0013 are `Accepted`. |
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
| Stalled `:ledger-server:test` cause | Not found. Open. |
| Sarv load test path and results | Path in the review not found in a shallow listing of Sarv's `tests/`. No results found. |
| Sarv claims other than the three spot checks | Not re-checked in this pass. |
| Fable design audit findings R-1 to R-20 | The report is not in the repo. Cannot be checked from the repo. |
| Owner's Java and .NET team skills | The repos hold no evidence. |
| Module skeleton and `/health` endpoint | Named in the session notes. Not checked in this pass. |

## 7. Next steps

Order of work, from the review (sections 20 and 21) and the decisions made in this session:

1. **Owner decides items 1 to 4 in section 8.** Nothing else should wait on these. Items 1 to 4 are: Oracle status, bucket to account mapping, asset list and scale, Account Service contract.
2. **Write the missing ADRs** (review section 21, item 2). They are not yet in the repo:
   - PostgreSQL, jOOQ and database roles. Replaces 0002 and 0028.
   - Account boundary, fence and seal, and the Wallet, Bucket and position model. Revises 0025 and 0024.
   - Transactional projection with an immutable chain. Replaces 0020.
   - Native holds. Replaces 0022.
   - Outbox and inbox on Kafka. Replaces 0021.
   - FX, settlement and reconciliation. Revises 0019 and 0029.
   - Payment timeline boundary, for the Payments service.
   - ADRs 0015, 0023, 0026, 0030, 0031 and 0032 need small fixes before the owner accepts them.
3. **Vertical spike** (review section 21, item 3). Build `PostTransaction` end to end on PostgreSQL 18, with the DDL from section 7.3, Flyway, the sorted-lock write path, the I-4 trigger, RLS, concurrency tests (duplicate key, A to B, simultaneous reversal), and a simple relay to Kafka. Fix capacity numbers only after this spike.
4. Then holds, account fence, FX and settlement, in that order. Then shadow migration from Sarv (review section 18, phase 1). Until then Sarv stays the reference system.
5. **Investigate the stalled `:ledger-server:test`** run. Find whether it is a real hang.
6. **Resolve the overlapping agent sets** (section 3.3). Owner choice.
7. **Usage fix, not applied.** Two changes were proposed and are waiting for the owner's answer:
   - Change A: add an `env` block to `~/.claude/settings.json` with `"CLAUDE_CODE_SUBAGENT_MODEL": "sonnet"`. The file has no `env` block today.
   - Change B: append a rule to `~/.claude/CLAUDE.md`. Do not pass model or effort on agent calls unless the owner asks in that turn. Announce long tasks.
   - Background: `general-purpose` is a built-in subagent with no editable file. Per the Claude Code docs, a per-call model overrides frontmatter, then the env var, then the main model. The heavy usage came from two `general-purpose` runs on the Fable model at extra-high effort, which the owner asked for. One of them hit the 5-hour limit.
   - Not recommended: `CLAUDE_CODE_SUBAGENT_MODEL_FORCE=1`.
8. **Commit plan** (owner asked for it; not done). Chunked commits per `CLAUDE.md`:
   - `docs(adr)`: ADRs 0014 to 0032, plus the amendment lines on 0002 to 0013 and `docs/adr/README.md`.
   - `docs(domain)` and `docs(security)`: `invariants.md`, `threat-model.md`, `checklist.md`.
   - `docs(architecture)`: the review.
   - `docs(handoff)`: this file.
   - `CHANGELOG.md`: in the chunk that matches the change.
   - Owner asked that commit dates be 24 March. The year and meaning are still being confirmed with the owner. Existing commits are dated 2026-03-22.
   - Push is blocked by `.claude/settings.json`. The owner must push by hand, or lift the deny rule.
   - Committing needs the `committer` subagent per the global rules.
9. **Send the Appendix A prompt to GPT** to get the owner's intended structure. Then run one independent read-only review of that output with an Opus agent at high effort, using the owner's eight-section format. The full list of section names is not in the repo, so ask the owner for it. The known names are "Overall verdict" (first) and "Final recommendation" (last).

## 8. Open owner decisions

Ranked by the review (section 20.1). Items 1 to 4 block the next work.

| # | Decision | Options | Agent recommendation |
|---|---|---|---|
| 1 | Is Oracle still a deployment requirement? | Yes or no | Confirm no. Needed before ADR 0028 can be rejected. |
| 2 | Bucket to Account mapping | (a) 1:1. (b) N:1 per customer. (c) Pooled omnibus. | (b). Affects seal, hot spot on the anchor, and whether freeze is local or external. |
| 3 | Asset list and scale | `bigint` or `NUMERIC(38,0)` | Not decided. A later change means rewriting the journal. |
| 4 | Contract with the Account Service | Events, `account_version`, synchronous freeze fence, close seal | Not decided. |
| 5 | Wallet rules | Several wallets per account? Wallets per owner? Credits to a suspended wallet? Overdraft on buckets? Product catalog (`product_code`)? Who supplies `external_account_id`? | Not decided. |
| 6 | Java or .NET | A: keep in the .NET monolith. B: extract as .NET. C: extract as Java. | Not decided. Repos hold no evidence about team skills. |
| 7 | Payment timeline | Owner in Payments, payload retention, DPO sign-off | Not decided. |
| 8 | RPO, RTO, legal retention, TPS and latency targets | Numbers | Not measured. |
| 9 | Hold policies | Over-capture, capture after freeze, default expiry | Not decided. |
| 10 | FX policies | Reporting currency, rate source, single-use quote | Not decided. |
| 11 | jOOQ or plain `JdbcClient` | | Not decided. jOOQ OSS is Apache 2.0, free only for the latest PostgreSQL versions. |
| 12 | Posting rights per wallet, account or class | Also optional activity topic and schema registry | Not decided. |
| 13 | Oracle JDBC driver licence exception | | Moot if Oracle is dropped. |
| 14 | Minimum Oracle version and edition | | Moot if Oracle is dropped. |
| 15 | Certificate-bound tokens as default | | Not decided. |
| 16 | REST in v1, or gRPC only | | Not decided. |
| 17 | Back-dating limit | Default 366 days | Default proposed in review. Owner to confirm. |

## 9. Required repo changes not yet made

Source: review section 19.2. None of these are done.

| File or area | Change |
|---|---|
| `CLAUDE.md` | Oracle to PostgreSQL 18. jOOQ allowed in persistence only. Module list adds `ledger-contracts`, `ledger-messaging-kafka`, `ledger-account-client`. `BigDecimal` rule narrowed to money amounts. Scopes list (including `wallets:*`). Write-path text. Commands and images (`postgres:18` with exact tag and digest). Account ownership table. |
| `ArchitectureTest` (`ledger-server/src/test/java/com/kimialedger/server/ArchitectureTest.java`) | Replace `oracle..` with `org.postgresql..` and `org.jooq..`. Add a Kafka rule. This tightens the rules. It does not loosen them. |
| `contracts/proto/ledger/v1` | Remove `account.proto` and `CreateAccount`. Add `external_account_id`. Remove `account_seq`. Use strings for amounts if the scale decision goes to NUMERIC. Add new RPCs for holds, FX, settlement, reconciliation, and fence. Add wallet and bucket RPCs. Add `ledger.events.v1`. Rename `Entry` to `Transaction`. Replace `sequence_no` and `correlation_id` with `position_seq` and `account_version`. Change `amount_minor` from `int64` to `string` if scale goes to NUMERIC. |
| Stale docs | `docs/integration.md`, `docs/adr/0003`, `contracts/README.md`. Named in the session notes. Not checked against the review in this pass. |
| Agent files (`.claude/agents/`) | `architect` cannot write ADRs but says it does. `api-dev` has a wrong proto path and says REST is transcoding. Others mention outbox and balance updates that the new design changes. |
| New ADRs | Listed in section 7, item 2. Not written. |

Note: review section 19.1 says ADR 0002 must be marked Superseded once its replacement is accepted. No ADR was changed to Superseded in this session.

## 10. Cost and model notes

| Work | Model and effort | Size |
|---|---|---|
| Design audit | Fable, extra-high | About 179k tokens, about 13 minutes |
| ADR drafting, ADRs 0014 to 0017 | Fable, extra-high. Cut off by the 5-hour rate limit. | Not measured |
| ADR drafting, ADRs 0014 to 0032 (completion) | Opus, high | About 212k tokens, about 18 minutes |
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
