# Architecture review, 2026-11-14

Reviewed: branch `chore/bootstrap` at `eeec73f` (Phase 1: skeleton, contracts, ADRs, agent setup; no domain code).

This is a review of decisions, because there is almost no Java yet. Each finding states the problem and the
decision recommended. Nothing in this document is decided until it is recorded in an ADR.

Item ids (`B-n`, `D-n`, `P-n`, `A-n`, `T-n`, `S-n`) exist so later ADRs, issues and commits can cite them.

## Verdict

The module structure, the dependency rules and the security baseline are sound. The weak points are in the
contract and in the ledger model. Most of them become permanent once the first migration runs and `ledger.v1`
is released, because the journal is immutable and the contract is additive-only. Fix section B before any
domain code is written.

## What was checked

| Check | Result |
|---|---|
| `./gradlew build --offline` | passes |
| `buf lint`, `buf format -d --exit-code`, `buf build` (in `contracts/`) | pass |
| `buf breaking`, form given in `CLAUDE.md` | fails: the path `.git` does not exist inside `contracts/` |
| `buf breaking`, form given in `contracts/README.md` | fails: `main` has no proto files yet (expected until first merge) |
| Gradle task `integrationTest` (used by the pre-commit hook) | does not exist |
| `git merge-base main HEAD` | none: the branch no longer shares history with `main` |

Not checked: licences and CVEs of current dependencies, the version numbers pinned in the catalog, anything
that needs a running database.

## Repository state to resolve first

The root commit of `chore/bootstrap` was rewritten on 2026-11-14 to change the author identity. The branch now
has no common ancestor with `main`. Until that is resolved:

- GitHub cannot open a pull request from this branch to `main`.
- `git diff main...HEAD`, which the `security-reviewer` agent starts from, fails with "no merge base".
- `buf breaking --against main` has no usable baseline.

Either rebase the six commits back onto the original root commit, or replace `main` on the remote. The second
option is a force-push and is a maintainer decision.

## B. Fix before the first migration or contract release

### B-1 Tenant-wide journal order cannot be guaranteed (I-18)

`sequence_no` is defined as strictly increasing per tenant, and pagination and event ordering depend on it.
Two concurrent transactions can commit in the opposite order to their sequence numbers. A reader that pages by
cursor then skips the entry that committed late. The only way to keep the invariant is one lock per tenant,
which makes every write in a tenant serial.

Decision: drop the tenant-wide ordering promise. Give each account a posting sequence that is assigned under
the account lock the write path already holds. `ListPostings` pages by `(account_id, account_seq)`.
`GetBalance` returns `as_of_account_seq`. Remove `sequence_no` from `Posting`, `Entry` and
`GetBalanceResponse`. Record tenant-wide total order as rejected for v1.

### B-2 REST is defined two contradictory ways

ADR 0004 says REST is gRPC-JSON transcoding. `ledger-api-rest` is a hand-written Spring MVC adapter. Spring
gRPC has no transcoding support (only an open feature request was found), so transcoding would mean an Envoy
filter in front of the service or a community library in the request path.

`error.proto` also promises HTTP 422 for `FAILED_PRECONDITION`. The standard gRPC-to-HTTP mapping gives 400.
Any consumer that reaches the service through a transcoding proxy would see 400.

Decision:
- Keep `ledger-api-rest` as hand-written controllers.
- Use the generated proto messages as its request and response types, serialised with the protobuf JSON
  mapping, so the JSON shape is identical to what transcoding would produce.
- Add a contract test: every route in the generated OpenAPI document is served, with the documented status.
- Use the standard status mapping (400 for `FAILED_PRECONDITION`, 409 for `ABORTED`). Remove 422 from the contract.
- Amend ADR 0004: "REST conforms to the transcoding mapping", not "REST is transcoding".

### B-3 Entries have no business date

An entry only has `posted_at`, the commit time. Back-dated entries, period close and reconciliation against
external statements need a caller-supplied date. It cannot be added to immutable rows later.

Decision: add `effective_at` to `Entry` and `PostEntryRequest`. It is caller-supplied, bounded (not in the
future beyond a small tolerance, not older than a configured limit), and defaults to `posted_at`.

### B-4 Reversal makes the caller rebuild the entry

To reverse, the caller sends mirrored postings and `reverses_entry_id`, and the server rejects any mismatch.
The server already has the original.

Decision: add `ReverseEntry(entry_id, idempotency_key)`; the server builds the mirror. Remove
`reverses_entry_id` from `PostEntryRequest`. State in `invariants.md`:
- whether a reversal can itself be reversed (recommended: no);
- that a reversal succeeds even if an account was closed after the original (recommended: yes, otherwise
  history cannot be corrected);
- whether a reversal may take an account below zero when `allow_negative_balance` is false (recommended: no,
  it fails with `INSUFFICIENT_FUNDS`).

### B-5 One message is used for input and output

`Posting` is both the request leg and the stored leg, and output-only fields are "ignored" on input.
Splitting the message later is a breaking change.

Decision: add `PostingInput` (`account_id`, `direction`, `money`) and use it in `PostEntryRequest`.

### B-6 `correlation_id` is a field in every request and response

It is a transport concern. In the generated OpenAPI it appears as a query parameter on every GET.

Decision: carry it as a header / gRPC metadata (`x-correlation-id`). Remove it from all request and response
messages. Keep it in the error detail.

### B-7 Idempotency is underspecified (ADR 0009, I-15)

Not stated today:
- whether `CreateAccount` and `PostEntry` share one key space;
- whether a failed request is stored and replayed, or evaluated again on retry;
- which fields the payload hash covers.

Decision:
- Key scope is `(tenant_id, operation, idempotency_key)`.
- Only successful writes are recorded. A request that failed is evaluated again on retry.
- The hash covers the business fields only, in a canonical encoding.
- The record stores the key, the hash and the id of the created entity. The response is rebuilt from the
  journal. Do not store a serialised copy of the response; it would be a second source of truth.

### B-8 Error contract gaps

- No code for lock timeout or contention. The threat model lists a lock timeout as a mitigation; today it
  would surface as `INTERNAL`, which tells the caller nothing about retrying.
- No code for a dependency being down.
- `ledger.v1.ErrorInfo` has the same name as `google.rpc.ErrorInfo`, and both can appear in the same
  `details` array.

Decision: add `ERROR_CODE_CONTENTION` (`ABORTED`, 409, retry with the same key) and `ERROR_CODE_UNAVAILABLE`
(`UNAVAILABLE`, 503). Rename the message to `LedgerErrorDetail`.

### B-9 Storage width does not match the wire

The API carries `int64`. ADR 0002 stores money as `NUMBER(38,0)`. A balance larger than `int64` could be
stored but never returned. Currency is `CHAR(3)`, which excludes any non-ISO unit forever.

Decision: `int64` is the limit everywhere and overflow is an error (I-8). Columns are `NUMBER(19,0)` on Oracle
and `BIGINT` on PostgreSQL. Make the currency column 12 characters wide and keep validating ISO-4217 only in v1.

### B-10 The breaking-change gate has the wrong baseline

`buf breaking` compares against `main`. The moment this branch merges, the current draft becomes the baseline
and every fix in this section is a "breaking change".

Decision: apply section B before merging. Compare against the latest release tag, not `main`. Until the first
release the contract is a draft and says so in `contracts/README.md`.

## D. Decisions to record as ADRs

| Id | Question | Recommendation |
|---|---|---|
| D-1 | Events: the outbox is in the write path, but there is no event contract, no broker choice and no relay module. | Cut events from v1. Events can be derived from the immutable journal later. Ship the outbox, the relay and `ledger.events.v1` together in the release that has a first consumer. |
| D-2 | Holds (authorise, then capture or void). | Out of scope for v1. Document the pattern with a per-customer hold account, which the N-leg model already supports. |
| D-3 | `external_ref` is not unique and there is no lookup by it. Every consumer needs its own mapping table, and a retry with a new key creates a duplicate account. | Make it unique per tenant for accounts. Add lookup by `external_ref` and `ERROR_CODE_ACCOUNT_ALREADY_EXISTS`. |
| D-4 | `CLOSED` and `ACCOUNT_CLOSED` exist, but no RPC closes an account. | Add `CloseAccount`. Closing requires a zero balance. |
| D-5 | Missing reads: `ListAccounts`, `ListEntries`. | Add both, paginated. |
| D-6 | The same account twice in one entry is undefined. | Allow it. Locks are taken once per distinct account. I-11 is checked on the net effect of the entry. |
| D-7 | Transaction boundary: the application layer has no Spring or JDBC but must run lock, validate, insert as one transaction. | Define a transaction port in `ledger-application` (see P-3). Without it the write logic ends up inside the SQL module. |
| D-8 | Generated Java stubs have no home; `buf.gen.yaml` only emits OpenAPI. | Add a `ledger-contracts` module (see T-8). |
| D-9 | Tenant identity: which claim, which issuers and audiences, where TLS terminates, whether tokens are bound to the client certificate, how a tenant comes to exist. | Fixed claim name, issuer and audience allow-list, and a tenant registry table. A token for an unknown or suspended tenant is rejected. Decide TLS termination before promising "fail closed". |
| D-10 | Rate limiting: in the service or at a gateway. | Decide once; `ERROR_CODE_RATE_LIMITED` is already in the contract. |
| D-11 | `metadata` is in I-20, ADR 0011, the checklist and the integration guide, but in no proto. The threat model calls it the most likely PII leak. | Remove it from the documents. Do not add it in v1. |
| D-12 | Default of `allow_negative_balance`: "open decision" in `invariants.md`, already fixed in the proto. | Make the field required on create. No type-derived default. |
| D-13 | `MAX_POSTINGS_PER_ENTRY` is "configurable" in I-2 but a fixed `2..100` in the published contract. | It is a contract constant. Remove "configurable". |
| D-14 | Unowned runtime pieces: reconciliation job (I-10), outbox relay, metrics and tracing. | Name the module and the owner for each when it is scheduled. |

## P. Two databases: Oracle and PostgreSQL

Both are feasible, and the current ArchUnit rules already cover it: they match `com.kimialedger.persistence..`,
not the Oracle package. No rule has to be loosened. ADR 0002 must be superseded by a new ADR.

### P-1 Layout: duplicate the SQL, share the tests

```
ledger-persistence-oracle      SQL and Flyway migrations for Oracle
ledger-persistence-postgres    SQL and Flyway migrations for PostgreSQL
ledger-application (testFixtures)   one port contract suite: behaviour, concurrency, tenant isolation
```

The SQL surface of a ledger is small (roughly fifteen statements). Two modules with their own SQL are simpler
than one module with a dialect abstraction, and each can use native features. Equal behaviour is guaranteed by
both adapters passing the same contract suite against a real database, not by shared code.

Add an ArchUnit rule that the two adapters do not depend on each other. Change the rule text in `CLAUDE.md`
from "all SQL lives in `ledger-persistence-oracle`" to "all SQL lives in `ledger-persistence-*`".

### P-2 Rules that keep the port implementable on both

| Topic | Rule |
|---|---|
| Ids and timestamps | Generated in the application through ports (`IdGenerator`, `Clock`). No sequences, identity columns, `RETURNING` or database clock. |
| Lock order (I-17) | The application sorts and de-duplicates account ids and locks one account per statement. Do not rely on `ORDER BY … FOR UPDATE` over an `IN` list: Oracle has shipped versions that did not lock in `ORDER BY` order. |
| Lock timeout | `FOR UPDATE WAIT n` on Oracle, `SET LOCAL lock_timeout` on PostgreSQL. Both map to `ERROR_CODE_CONTENTION`. |
| Idempotency claim | It is the first statement of the write transaction. On a duplicate, roll back the whole transaction and read the winner. PostgreSQL aborts a transaction on a unique violation; Oracle does not. |
| Empty strings | Oracle stores `''` as `NULL`. Optional text is "absent or non-empty" in the domain; the two are never distinguished. |
| Isolation | `READ COMMITTED` plus explicit row locks on both. Nothing depends on serializable isolation. |
| Pagination | Keyset on `(account_id, account_seq)` with `FETCH FIRST n ROWS ONLY`, which both support. |
| Append-only | Oracle: grants, then immutable tables. PostgreSQL: revoke `UPDATE`, `DELETE`, `TRUNCATE`, plus a trigger that raises. Each adapter has a test proving the runtime user cannot update or delete a journal row. |
| Migrations | One set per adapter module. Never shared, never generated from one another. |
| Selection | `ledger.persistence.vendor` is required at start-up. No default; a missing value fails the start. |

### P-3 Transaction port (sketch, names indicative)

```java
// ledger-application
interface LedgerTransaction {
  <T> T execute(TenantId tenant, Function<UnitOfWork, T> work);
}

interface UnitOfWork {
  boolean claimIdempotencyKey(Operation op, IdempotencyKey key, PayloadHash hash); // first statement
  List<AccountState> lockAccounts(SortedSet<AccountId> ids);                       // sorted, distinct
  void appendEntry(Entry entry);                                                    // entry + postings + balances
}
```

The use case in `ledger-application` orchestrates; validation stays in `ledger-core`; the adapters only
execute statements.

### P-4 Cost and licence

- Every persistence feature is written twice and tested twice. That is the price of two first-class databases.
- CI runs the contract suite against both databases on every pull request. A failure on either blocks the merge.
- PostgreSQL is the default for local development, because the container starts fast.
- The Oracle JDBC driver is published under the Oracle Free Use Terms and Conditions. That is not on the
  project's allowed list (Apache-2.0, MIT, BSD, EPL), and Oracle's download page names a different licence
  again. Record an explicit exception in the new ADR and read the redistribution terms before shipping an
  image that contains the driver. If redistribution is not allowed, the operator supplies the driver.
- The PostgreSQL driver is BSD-2-Clause.

## A. Agents

### What each agent does today

| Agent | Model | Tools | Owns |
|---|---|---|---|
| `architect` | inherit | Read, Grep, Glob | The plan. Described as writing ADRs, but cannot write. |
| `domain-dev` | inherit | read, write, Bash | `ledger-core`, `ledger-application`, `ledger-persistence-oracle`, migrations |
| `api-dev` | sonnet | read, write, Bash | `contracts/`, `ledger-api-rest`, `ledger-api-grpc` |
| `test-engineer` | sonnet | read, write, Bash | All tests, written after the implementation |
| `security-reviewer` | inherit | read, Bash (read-only by instruction) | Checklist review of the diff |
| `docs-writer` | haiku | read, write | All markdown, including ADRs and `invariants.md` |
| `git-committer` | haiku | Bash, read | Staging, commits, PR text |

### Findings

- **A-1 Unowned work.** No agent owns `ledger-server` (wiring, token and scope configuration, the ArchUnit
  tests), the build files and version catalog, `.githooks`, CI, the container image, releases or Renovate.
  Add a `platform-dev` agent for these.
- **A-2 The most precise documents are written by the weakest model.** ADRs and `invariants.md` go to
  `docs-writer` on haiku, relayed from the architect's plan. Give `architect` write access limited to
  `docs/adr/**` and `docs/domain/invariants.md`. `docs-writer` keeps README, CHANGELOG, CONTRIBUTING and the
  integration guide.
- **A-3 Tests are written after the code, by reading the code.** Tests written that way tend to confirm the
  implementation. `test-engineer` writes the property tests from `invariants.md` and the ports first; they fail;
  then the dev agent implements. Integration and concurrency tests follow the implementation. Put
  `test-engineer` on the same model as the dev agents: the tests are the safety net.
- **A-4 Nobody reviews correctness.** `security-reviewer` walks a checklist. Add a second `architect` pass
  after implementation: the diff against the plan and the invariants. It needs read-only git access for that.
- **A-5 Agent prompts restate `CLAUDE.md` and have drifted.**
  - `api-dev` cites `contracts/ledger/v1/*.proto`; the files are in `contracts/proto/ledger/v1/`.
  - `api-dev` says REST is transcoding (B-2).
  - `test-engineer` says JUnit 5; the catalog pins JUnit 6.0.3.
  - The Oracle image is "slim" in one place and "slim-faststart" in another.
  - `domain-dev` and `test-engineer` hard-code Oracle as the only database.

  Prompts should hold the role, the owned paths and the report format, and refer to `CLAUDE.md` for rules.
- **A-6 Two checklist items cannot be performed by any agent.** G1 asks for "no known CVEs" and a licence
  check; `security-reviewer` has only git and grep. "Verify the current version before pinning" needs network
  access no agent is told to use. Automate both in the build (T-6); the agent reads the report.
- **A-7 The workflow has gaps.**
  - No rule for a failed review. State it: a FAIL goes back to the owning dev agent, then through tests and
    review again.
  - No fast path. A typo fix does not need an architect plan and a test pass.
  - No contract-first order for a feature that touches the API and the domain.
- **A-8 Persistence needs its own owner once there are two databases.** Add `persistence-dev` for both
  adapters, their migrations, grants and the contract suite. `domain-dev` keeps core and application.
- **A-9 Rules that are only instructions.** "Never use `--no-verify`" and "read-only Bash" are prompt text.
  Back them with permission rules (T-3).
- **A-10 Name collisions with plugin agents.** The session also offers `ecc:architect`, `arxitect:architect`,
  `ecc:security-reviewer` and `agent-skills:test-engineer`. See T-13.

### Proposed set and order

| Agent | Owns |
|---|---|
| `architect` | Plan; ADRs and `invariants.md`; post-implementation conformance review |
| `api-dev` | `contracts/`, `ledger-contracts`, both inbound adapters |
| `domain-dev` | `ledger-core`, `ledger-application` |
| `persistence-dev` | Both persistence adapters, migrations, grants |
| `platform-dev` | `ledger-server`, build, hooks, CI, image, release |
| `test-engineer` | Property tests first; integration, concurrency, isolation after |
| `security-reviewer` | Checklist review; reads scanner reports |
| `docs-writer` | README, CHANGELOG, CONTRIBUTING, integration guide |
| `git-committer` | Commits and PR text |

Feature order: `architect` plan → `api-dev` contract → `test-engineer` property tests → `domain-dev` →
`persistence-dev` → `api-dev` adapters → `test-engineer` integration → `security-reviewer` and `architect`
review → `docs-writer` → `git-committer` → human approval.

## T. Tooling

### Installed on the development machine

JDK 25.0.5, buf 1.75.0, gitleaks 8.30.1, Docker 29.9.3, gh 2.46.0, jq 1.8.1. `core.hooksPath` is set to
`.githooks`.

Not installed: grpcurl, actionlint, hadolint, osv-scanner, trivy, cosign, syft, psql, SQLcl.

### Findings

- **T-1 There is no CI.** `.github/` does not exist. Every gate the documents describe (`buf breaking`,
  gitleaks, CodeQL, dependency review, pinned actions) is currently enforced by nothing except an opt-in local
  hook. Minimum set: build and tests against both databases; buf lint, format and breaking; a check that the
  generated OpenAPI is up to date; gitleaks; dependency review; CodeQL. Adding workflows needs maintainer
  approval under the project rules.
- **T-2 Protect `main` on the server.** The deny list in `.claude/settings.json` matches command text and can
  be bypassed by a different spelling of the same command. Branch protection on GitHub (pull request required,
  required checks, no force-push) is the real control.
- **T-3 `.claude/settings.json`.**
  - Deny: `git commit` with `--no-verify`; edits to `contracts/openapi/**` (generated); reads of `.env*`,
    `*.pem`, `*.p12`, `*.jks`.
  - Ask: history rewriting (`git rebase`, `git commit --amend`, `git branch -D`, `git clean`); edits to
    `ArchitectureTest.java`, `docs/security/checklist.md`, `.githooks/**`, `.claude/**` and
    `gradle/libs.versions.toml`.
  - Allow: routine read-only commands (`./gradlew build|test|spotlessCheck`, `buf lint|format|build|breaking`,
    `git status|diff|log|show`), to cut prompts.
- **T-4 The pre-commit hook has a bug.** It runs `test -x integrationTest`. That task does not exist, so the
  first command always fails, its error is discarded, and the fallback runs without `--offline`. Define
  `integrationTest` with the JVM Test Suite plugin (unit and architecture tests in `test`, Testcontainers in
  `integrationTest`) and let the hook run only the fast set.
- **T-5 Dependency integrity.** The catalog pins direct dependencies by version. Add Gradle dependency
  verification (`gradle/verification-metadata.xml`, SHA-256) so every artifact, including transitive ones, is
  hash-pinned.
- **T-6 Licence and vulnerability checks are manual.** Add a licence report plugin with an allow-list that
  fails the build, a vulnerability scan in CI (osv-scanner or OWASP dependency-check), and the CycloneDX
  plugin for the SBOM that `NOTICE` already refers to.
- **T-7 Static analysis promised but absent.** ADR 0006 says Error Prone or ArchUnit will forbid
  floating-point types in core. Neither does today. Add the ArchUnit rule now (no `float`, `double`, `Float`,
  `Double` in core or application). Add Error Prone with NullAway when domain code starts.
- **T-8 Java code generation for the protos.** Use the Gradle protobuf plugin in `ledger-contracts`, with a
  pinned `protoc` artifact. It is hermetic and cached. Keep buf for lint, breaking and OpenAPI. Note that
  OpenAPI generation uses a remote plugin on the Buf Schema Registry, so regenerating needs network access to
  that service.
- **T-9 Limits are prose.** String lengths, `2..100` postings and the page size maximum live in comments.
  Both adapters would hand-code them separately. Annotate the protos with protovalidate and validate once, the
  same way, in both adapters.
- **T-10 OpenAPI generator.** The gnostic output is OpenAPI 3.0.3, has no `securitySchemes`, documents only a
  `default` error response, and types error details as `Any`. Evaluate `protoc-gen-connect-openapi`
  (OpenAPI 3.1; reads `google.api.http` and, partly, protovalidate constraints). This is an evaluation, not a
  decision.
- **T-11 Property testing library.** jqwik is in maintenance mode: dependency updates and critical fixes only.
  No statement on JUnit 6 compatibility was found. Before adding it, run a spike on JUnit Platform 6.0.3.
  If it does not run, decide the fallback in an ADR.
- **T-12 Proving the tests are real.** "Every invariant gets a property test" is a claim. Mutation testing
  (PIT) on `ledger-core` only checks it. Add when core has code.
- **T-13 Session plugins.** Sessions in this repository load many unrelated plugins (Unity, design, marketing
  and others). Several inject mandatory-instruction text and register agents with the same names as the
  project's. Disable the unrelated ones for this project so the project's own agents and rules are the only
  ones in play.
- **T-14 Local development.** There is no way to run the service with a database or a token issuer. Use
  Spring Boot's Testcontainers support at development time (`bootTestRun`) instead of compose files, plus a
  development-only token issuer container.
- **T-15 Repository files missing.** `.editorconfig`, `.gitattributes` (line endings for `gradlew.bat`),
  `CODEOWNERS`, a pull-request template that carries the security checklist, `renovate.json`.
- **T-16 Worth installing locally.** grpcurl (gRPC smoke tests), actionlint (before the first workflow),
  hadolint (before the first Dockerfile), osv-scanner. cosign and syft are needed in CI only.

## S. Smaller defects

- **S-1** `/health` is served on the API port. Move management endpoints to a separate port.
- **S-2** `core_depends_on_jdk_only` allows all of `java..`, which includes `java.net` and `java.io`.
  Narrow it to an allow-list when core has code.
- **S-3** `allowEmptyShould(true)` and `withOptionalLayers(true)` make the ArchUnit rules pass on empty
  modules. They are marked for removal in Phase 2; removing them is part of the first domain change.
- **S-4** ADR 0004 gives the proto path as `contracts/ledger/v1/`; it is `contracts/proto/ledger/v1/`.
- **S-5** The `buf breaking` command in `CLAUDE.md` uses `.git`; from inside `contracts/` it must be `../.git`.
- **S-6** Not stated anywhere: whether an account with a non-zero balance can be closed (see D-4).
- **S-7** `Account.type` and `Account.currency_code` are immutable according to proto comments, but that is
  not a numbered invariant.

## Suggested order of work

1. Resolve the branch ancestry.
2. ADRs: supersede 0002 (two databases), amend 0004 (REST), new ADRs for B-1, B-3, B-4, B-7, D-1 and D-9.
3. Apply section B to the protos in one pass; regenerate OpenAPI; update `invariants.md`.
4. Agents, permission rules and the hook fix (A, T-3, T-4).
5. CI and branch protection (T-1, T-2), with maintainer approval.
6. Phase 2: domain code, starting with the transaction port and the property tests.

## Sources and confidence

Checked against external sources on 2026-11-14:

- Spring gRPC and transcoding: [Spring gRPC getting started](https://docs.spring.io/spring-grpc/reference/getting-started.html),
  [feature request](https://github-users.yhajoa.workers.dev/spring-projects/spring-grpc/issues/339),
  [community transcoding library](https://danielliu1123.github.io/grpc-starter/docs/extensions/grpc-http-transcoding).
- jqwik maintenance status: [project README mirror](https://awesome.ecosyste.ms/projects/github.com%2Fjqwik-team%2Fjqwik);
  JUnit 6: [release notes 6.0.3](https://docs.junit.org/6.0.3/release-notes.html).
- Oracle JDBC licence: [Maven Central, ojdbc17](https://central.sonatype.com/artifact/com.oracle.database.jdbc/ojdbc17),
  [Oracle JDBC downloads](https://www.oracle.com/database/technologies/jdbc-upc-downloads.html).
- OpenAPI generator: [protoc-gen-connect-openapi](https://pkg.go.dev/github.com/sudorandom/protoc-gen-connect-openapi@v0.15.25).
- Lock order with `FOR UPDATE ORDER BY` on Oracle: [orasql.org](https://orasql.org/2013/02/16/workaround-for-deadlock-with-select-for-update-order-by-on-11-2-0-2-11-2-0-3/),
  [jOOQ blog](https://blog.jooq.org/how-to-avoid-the-dreaded-dead-lock-when-pessimistic-locking-and-some-awesome-java-8-usage/).

Not verified, confirm before acting:

- Whether the Oracle driver licence permits redistribution inside a container image.
- Whether jqwik runs on JUnit Platform 6.
- Whether Flyway's open-source edition supports the Oracle version in use.
- Whether Error Prone, NullAway and PIT support Java 25.
- The lock-order statement for Oracle rests on reports about specific older versions; the per-statement
  locking in P-2 avoids depending on it either way.
