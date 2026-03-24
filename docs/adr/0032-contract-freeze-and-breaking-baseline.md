# ADR 0032: Contract freeze, breaking-change baseline and client tolerance

Status: Proposed · Date: 2026-11-14 · Amends 0004

## Context
Review finding B-10; audit finding R-13. `buf breaking` compares against `main`. Once the draft merges, every
fix from section B of the review counts as breaking. ADR 0004 and `contracts/README.md` call additive changes
safe. They are safe on the binary wire, where unknown enum values are kept, but protobuf JSON parsers commonly
reject an unknown enum name or field unless configured to ignore it (behaviour of the Java and C# parsers:
unverified, human to confirm with the first consumer). Some changes pass `buf breaking` but still break
callers.

## Decision
1. **Draft until `v1.0.0`.** Until the first release tag, `ledger.v1` is a draft and may change in any way.
   `contracts/README.md` says so. `buf breaking` runs but does not block.
2. **The contract freezes at the tag `v1.0.0`.** Preconditions: the proto changes from ADRs 0014–0031 are
   applied, the first consumer has built against a release candidate, and these ADRs are accepted.
3. **From `v1.0.0`, `buf breaking` blocks** and compares against the latest release tag, not `main`. Rule set
   `FILE` stays.
4. **Changes that pass `buf breaking` and are still breaking** are forbidden inside `ledger.v1`: lowering a
   published limit, rejecting input that was accepted, changing a code's gRPC status, HTTP status or
   "recorded" flag (ADR 0018), changing a default, changing the canonical hash encoding for an existing hash
   version (ADR 0017).
5. **Clients must tolerate** unknown fields, unknown enum values (including `ErrorCode` and `AccountStatus`),
   and errors without `LedgerErrorDetail` (ADR 0018). The integration guide shows the parser settings for .NET
   and Java. The server stays strict on input (ADR 0015).
6. The contract artifact of each release carries its tag; consumers pin it (ADR 0012).

## Consequences
- The section-B fixes can merge to `main` as normal changes.
- After `v1.0.0`, adding an `ErrorCode` value is allowed only because clients were told to tolerate it.
- The `buf breaking` command in `CLAUDE.md` and `contracts/README.md` changes (follow-up; it also has the
  wrong path from inside `contracts/`, review S-5).

## Alternatives rejected
- **Compare against `main` (current).** Freezes a draft by merging it.
- **Freeze at first merge.** Same effect.
- **Enums as strings or integers in the contract to avoid JSON parser failures.** Loses the schema; the
  tolerance rule is cheaper.

## Relation to the review and the audit
Agrees with B-10. Adds the freeze preconditions and the list of non-`buf` breaking changes; accepts R-13.
