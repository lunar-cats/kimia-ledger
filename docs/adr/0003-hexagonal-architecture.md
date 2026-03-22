# ADR 0003: Hexagonal architecture (ports and adapters)

Status: Accepted · Date: 2026-11-13

## Context
The ledger must be reachable over REST and gRPC today and over a message broker later, without duplicating
business rules or coupling the domain to any transport or database.

## Decision
- `ledger-core`: pure domain (JDK only). `ledger-application`: use cases and ports (interfaces).
- Inbound adapters (`ledger-api-rest`, `ledger-api-grpc`, later a broker consumer) are **thin**: validate shape
  and limits, map to a use-case command, map the result back. No business logic.
- Outbound adapters (`ledger-persistence-oracle`, outbox publisher) implement application ports.
- `ledger-server` wires everything with Spring Boot and holds no logic.
- Dependency direction is inward only and is enforced by ArchUnit tests that fail the build.

## Consequences
- Every ledger rule exists exactly once, in core, and is tested there with property-based tests.
- Adding a transport is a new module plus wiring; the domain does not change.
- Some mapping boilerplate between DTOs, commands and domain types is the accepted price.
