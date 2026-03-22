# ADR 0012: No client SDKs; published contracts are the integration surface

Status: Accepted · Date: 2026-11-13

## Context
Shipping SDKs for every consumer language multiplies release work and couples consumers to this repository's
release cycle. The first consumer is .NET.

## Decision
- This repository ships **no client libraries**.
- Each release publishes a **contracts artifact**: the `ledger/v1` proto files with their Google API
  dependencies, and the generated OpenAPI document, both versioned with the release.
- Consumers generate clients themselves (`Grpc.Tools`, `protoc`, `buf generate`, OpenAPI generators). See `docs/integration.md`.

## Consequences
- Compatibility is guaranteed at the contract level only (`buf breaking`), not at a library level.
- Breaking changes are visible to every consumer as a new package version, never as a silent SDK bump.
