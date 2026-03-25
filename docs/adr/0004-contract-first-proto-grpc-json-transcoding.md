# ADR 0004: Contract-first API: proto is the source of truth, REST via gRPC-JSON transcoding

Status: Accepted · Date: 2026-11-13

Amended by ADR 0019 (Proposed). The text below is unchanged; where it conflicts with an accepted amendment, the amendment wins.

## Context
Consumers (first: a .NET service in another repository) need stable, versioned contracts and generate their
own clients. REST and gRPC must both be first-class and must never drift apart.

## Decision
- `contracts/ledger/v1/*.proto` is the **single source of truth**. Package `ledger.v1`.
- REST is defined by `google.api.http` annotations on the same RPCs (gRPC-JSON transcoding). The OpenAPI
  document is **generated** from proto and never hand-edited.
- Contracts are **versioned per release** and published as release artifacts (proto bundle + OpenAPI).
- `buf lint` and `buf breaking` (against `main`) gate every change. Breaking changes require a new package version (`ledger.v2`).
- Amounts on the wire are `string` or `int64` minor units with an ISO-4217 code. Never floating point.
- Errors use a stable `ErrorCode` enum carried in gRPC status details and in the REST error body.

## Consequences
- Proto limits the API vocabulary (no REST-only features); that is intended.
- Consumers never depend on this repository's code, only on the published contract artifacts.
- The OpenAPI generator and the transcoding runtime are build dependencies to pin and maintain.
