# ADR 0031: ledger-contracts module for generated Java and the proto mapping

Status: Proposed · Date: 2026-11-14

## Context
Review findings D-8, T-8. `buf.gen.yaml` only emits OpenAPI; generated Java stubs have no home. ADR 0015 makes
both inbound adapters use the proto messages, so both need the same mapping from proto messages to use-case
commands and back. `CLAUDE.md` forbids proto types in `ledger-application`.

## Decision
1. New module **`ledger-contracts`**, package `com.kimialedger.contracts`, holding:
   - Java and gRPC stubs generated from `contracts/proto` at build time by the Gradle protobuf plugin with a
     pinned `protoc` artifact (not committed);
   - the single mapping between proto messages and use-case commands and results;
   - shape and limit validation of requests (one place for both transports).
2. Dependencies: `ledger-contracts` → `ledger-application`, `ledger-core`. Both inbound adapters →
   `ledger-contracts`. `ledger-core` and `ledger-application` must not depend on it (ArchUnit).
3. buf stays for lint, format, breaking checks and OpenAPI generation.
4. Licences and exact versions of the protobuf plugin, `protoc`, protobuf-java and grpc-java are checked
   before they are added (CLAUDE.md rule).

## Consequences
- One mapping, tested once; REST and gRPC cannot drift in how they read a request.
- The module is internal. Consumers still use the published proto files (ADR 0012).
- Build needs no network for code generation once the artifacts are cached.

## Alternatives rejected
- **Generated code only in `ledger-contracts`, mapping in a further module.** One more module for no gain.
- **Mapping in each adapter.** Two copies; they drift.
- **Mapping in `ledger-application`.** Proto types in the application layer.
- **Commit generated Java.** Large diffs, and a second source of truth next to the protos.
- **`buf generate` with remote plugins for Java.** Network access in every build.

## Relation to the review
Agrees with D-8 and T-8. Adds the mapping and validation to the module's job.
