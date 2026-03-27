# Contributing

Thank you. This is a ledger: correctness and security beat speed. Small, reviewed steps.

## Before you start
- Read `CLAUDE.md` (architecture and rules), `docs/adr/` (why things are the way they are) and
  `docs/domain/invariants.md`.
- Open an issue for anything beyond a small fix so the design can be agreed first.

## Setup
- JDK 25, Docker (for Testcontainers), `gitleaks`, `buf`.
- `git config core.hooksPath .githooks` enables the pre-commit hook (secret scan, format, fast tests).
- `./gradlew build` must be green before and after your change.

## Rules of the road
- Module boundaries are enforced by ArchUnit; never loosen a rule to make a build pass.
- All SQL and jOOQ in the PostgreSQL persistence module (`ledger-persistence-postgres`; the build still calls it
  `ledger-persistence-oracle`, pending rename (ADR 0014)); no framework types in `ledger-core`/`ledger-application`.
- Money is integer minor units (`NUMERIC(38,0)` / `BigInteger`). No floats. Postings are append-only.
- Every invariant change updates `docs/domain/invariants.md` and gets a property-based test.
- API changes go through `contracts/` first; `buf lint` and `buf breaking` must pass.
- New dependency: Apache-2.0/MIT/BSD/EPL licence only, exact version, justified in the PR.
- Never commit secrets or real-looking data. Fixtures are synthetic.

## Branches, commits, PRs
- Branch from `main`: `feat/…`, `fix/…`, `docs/…`, `chore/…`. Never commit to `main`.
- **Conventional Commits** (`feat(core): …`, `fix(persistence): …`, `docs: …`).
- **Chunk your commits**: one logical concern per commit. No AI or tool attribution trailers in commit messages.
- PR description: what, why, how to verify, security notes (walk `docs/security/checklist.md`), CHANGELOG entry under Unreleased.
- Every PR gets a security review against the checklist and at least one maintainer approval.

## Code style
Formatted by Spotless (google-java-format); `./gradlew spotlessApply`. Warnings are errors.
