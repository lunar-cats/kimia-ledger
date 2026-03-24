# ADR 0028: Oracle only in v1; driver licence exception; immutable tables before production

Status: Proposed · Date: 2026-11-14 · Amends 0002

## Context
Review section P; audit finding R-8. The review shows that Oracle plus PostgreSQL is feasible (two adapter
modules, one shared contract test suite) and that the Oracle JDBC driver's licence is not on the allowed list
in `CLAUDE.md` (Apache-2.0, MIT, BSD, EPL).

Licence facts, all unverified, human to confirm against the licence file shipped with the exact driver
version:
- Maven Central lists `com.oracle.database.jdbc:ojdbc17` under the Oracle Free Use Terms and Conditions (per
  the review).
- Secondary sources describe Oracle's free licence as allowing redistribution of unmodified programs if no fee
  is charged to end users. Oracle's own page could not be read (HTTP 403, 2026-11-14). Other Oracle JDBC
  terms (for example those Google publishes for Looker) forbid redistribution.

Immutable-table facts (R-8), all unverified, human to confirm on the target version: retention periods with
minimums, no `TRUNCATE`, no `UPDATE`, restricted column changes, availability depending on version and
release update.

## Decision
1. **v1 supports Oracle only.** PostgreSQL is not built in v1. The port and the rules below keep it possible.
2. **Rules adopted now because they cost nothing:** ids generated in the application; domain times from the
   `Clock` port (ADR 0020); no sequences, identity columns or `RETURNING` in the write path; optional text is
   "absent or non-empty"; `READ COMMITTED` plus explicit locks; keyset pagination with `FETCH FIRST n ROWS
   ONLY`. ArchUnit rules keep matching `com.kimialedger.persistence..`.
3. **Driver licence: a recorded exception, pending human sign-off.** The Oracle driver is a runtime-only
   dependency of `ledger-persistence-oracle` and `ledger-server`. Until a human has read the terms of the
   pinned version: published container images **do not contain** the driver; the operator supplies it.
   Local builds and tests may download it.
4. **Minimum Oracle version is a human decision.** Candidates: 19c at a release update that has immutable
   tables, or 23ai/26ai. Development uses Oracle AI Database Free (`gvenzl/oracle-free`). Flyway open-source
   support for the chosen version is unverified.
5. **Immutable tables** for entries, postings, reversal links, closures and idempotency records are decided
   before the first production deployment, on the chosen version. If adopted, tests run against immutable
   tables in Oracle Free from that point (support in Free unverified). Schema changes to those tables become
   new tables plus views; Flyway migrations never alter them.
6. Edition-dependent features (VPD, partitioning, Data Guard) are not assumed (ADR 0027, 0029).

## Consequences
- One persistence module, one migration set, one CI database.
- An operator cannot run the published image without obtaining the driver, until the exception is signed off.
- Adding PostgreSQL later means a second adapter, its own migrations, and a port contract suite shared by both.
  On PostgreSQL `SELECT … FOR UPDATE` needs an UPDATE privilege on at least one column (PostgreSQL
  documentation, unverified here); the insert-only grant model of ADR 0020 would need a lock-only column.
- `CLAUDE.md`'s licence rule needs a named exception, or the rule wins and the driver ships with no image.

## Alternatives rejected
- **Oracle and PostgreSQL in v1 (review P).** Every persistence feature written and tested twice before the
  first domain code exists. The first deployment runs on Oracle. Adding PostgreSQL does not remove the driver
  question for Oracle deployments.
- **PostgreSQL only.** Contradicts the first deployment's database.
- **Bundle the driver now and check later.** Distribution is the act the licence governs.

## Relation to the review and the audit
**Differs from review P**: one database in v1, with the review's portability rules kept. Takes P-4's licence
point and makes it a sign-off item. Records R-8 as unverified and the version as a human decision.
