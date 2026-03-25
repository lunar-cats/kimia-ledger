# ADR 0026: Module structure: transaction port in ledger-application and the ledger-contracts module

Status: Proposed · Date: 2026-11-15 · Relates to 0003, 0004, 0012

Replaces earlier drafts: old 0030, old 0031 (both first written 2026-11-14; revised here for PostgreSQL, jOOQ and
Kafka; revised 2026-11-15: mapping moved from `ledger-contracts` to the adapters).

## Context
Review findings D-7, D-8, P-3, T-8; review §19.1 (both ACCEPT, methods and packages updated). `ledger-application` may not import Spring, JDBC or proto
types, yet the use case must run lock, validate and write as one transaction (ADR 0017 D5). Without a port, the
orchestration moves into the SQL module and the business rules follow it. `buf.gen.yaml` only emits OpenAPI;
generated Java stubs have no home, and each inbound adapter maps proto messages to use-case
commands itself. The conformance tests of ADR 0019 D1.7 guard the two mappings.

The first D2 (old 0031) put the proto-to-command mapping and request validation in `ledger-contracts`. The review of
2026-11-15 rejected that. It contradicts ADR 0003 (inbound adapters validate shape and map to commands). A
generated-code module should not depend inward on the use-case layer. `ledger-messaging-kafka` (it needs the
`ledger.events.v1` stubs) and `ledger-account-client` would also pull in mapping they do not need.

## Decision

### D1. Transaction port in ledger-application (old 0030)
1. `ledger-application` defines the port. Names are indicative:

```java
interface LedgerTransactions {
  <T> T inWriteTransaction(TenantId tenant, Deadline deadline, Function<WriteUnitOfWork, T> work);
}

interface WriteUnitOfWork {
  Optional<IdempotencyRecord> findIdempotencyRecord(IdempotencyKey key);  // after the advisory lock
  List<LockedAnchor> lockAccountAnchors(List<ExternalAccountId> sorted);  // ① FOR KEY SHARE
  LockedWallet lockWallet(WalletId wallet);                               // ②
  List<LockedPosition> lockPositions(List<PositionId> sorted);            // ③
  List<LockedHold> lockHolds(List<HoldId> sorted);                        // ④
  void appendTransaction(Transaction transaction);                        // transaction and postings
  void updatePosition(PositionUpdate update);
  void updateWalletStatus(WalletStatusChange change);
  void appendOutbox(OutboxEvent event);
  void recordOutcome(IdempotencyRecord record);                           // last statement
}
```

2. The use case orchestrates; `ledger-core` validates; the adapter only executes statements, in the lock order of
   ADR 0017 D4.
3. **Outcomes are values.** A rejection is returned by `work`, and the port commits (the idempotency record must
   persist, ADR 0018 D3). Only failures without an outcome leave the port as typed exceptions defined in
   `ledger-application`: `Contention`, `IdempotencyRace`, `Unavailable`. Each rolls back.
4. `IdempotencyRace` makes the use case read the winner in a new read and return replay or conflict.
5. The adapter in `ledger-persistence-postgres` implements the port with Spring's transaction support and jOOQ
   (ADR 0014 D2), applies the deadline with `SET LOCAL` timeouts (ADR 0017 D7), and maps PostgreSQL errors
   (`55P03`, `57014`, `40P01`, unique violations) to the typed exceptions.
6. Reads use a separate read port without locks.
7. Clock and id generation are ports too (`Clock`, `IdGenerator`), implemented in `ledger-server`.

### D2. ledger-contracts module (old 0031; mapping moved to the adapters on 2026-11-15)
1. Module **`ledger-contracts`**, package `com.kimialedger.contracts`, holds **only** the Java and gRPC stubs
   generated from `contracts/proto` (`ledger.v1` and `ledger.events.v1`, ADR 0024 D2). The Gradle protobuf plugin
   generates them at build time with a pinned `protoc` artifact; the generated code is not committed. The module
   has no hand-written code.
2. Dependencies:
   - `ledger-contracts` depends on no project module, only on third-party libraries (protobuf-java, grpc-stub). Their
     licences and versions are checked before they are added (`CLAUDE.md` rule).
   - `ledger-api-rest` and `ledger-api-grpc` → `ledger-contracts`, `ledger-application`, `ledger-core`.
   - `ledger-messaging-kafka` → `ledger-contracts` (events), `ledger-application`, `ledger-core`.
   - `ledger-account-client` → `ledger-application`, `ledger-core`, plus the stubs of the Account Service contract
     (where that contract lives is open, see ADR 0022 Open items).
   - ArchUnit: `ledger-contracts` must not depend on any other project module. `ledger-core` and `ledger-application`
     must not depend on `ledger-contracts`.
3. The mapping between proto messages and use-case commands and results is done inside each inbound adapter (ADR
   0003), by mechanical field copying. Semantic validation and limits (100 postings, amount range, string lengths)
   live in the application commands and in `ledger-core`, so the adapters cannot diverge in meaning. REST and gRPC
   drift in the mapping is caught by the conformance tests of ADR 0019 D1.7.
4. buf stays for lint, format, breaking checks and OpenAPI generation.
5. If duplicated mapping becomes a real cost, a shared mapping module may be added later. It depends on
   `ledger-contracts` and `ledger-application`, never the other way round. That needs its own ADR.

## Consequences
- The write algorithm of ADR 0017 D5 lives in one use case and is testable with a fake unit of work. An adapter
  cannot reorder the steps; it has no access to the validation.
- No checked exceptions cross the module boundary; no generic `RuntimeException` either.
- One mapping per inbound adapter (REST and gRPC). The duplication is an accepted cost; the conformance tests of ADR
  0019 D1.7 guard against drift.
- `ledger-messaging-kafka` and `ledger-account-client` no longer get use-case mapping through `ledger-contracts`.
- The module is internal; consumers still use the published proto files (ADR 0012). Code generation needs no network
  once artifacts are cached.

## Open items
- The Gradle build still has `ledger-persistence-oracle` and lacks the new modules (`CLAUDE.md`); renaming them and
  the ArchUnit rules is pending work.

## Alternatives rejected
- **Spring `@Transactional` on the use case.** Framework import in `ledger-application`.
- **Repository methods that each open their own transaction.** Breaks atomicity.
- **The whole write as one adapter call (`postTransaction(command)`).** Moves orchestration and the rule order into
  the SQL module.
- **A claim-first idempotency method.** The outcome is recorded last (ADR 0018 D5).
- **Mapping and validation inside `ledger-contracts` (earlier draft old 0031).** Contradicts ADR 0003, makes generated
  code depend inward, pulls use-case code into Kafka and account-client. Rejected.
- **Shared mapping module now.** One more module before duplication is a measured cost; kept as the fallback in D2.5.
- **Mapping in `ledger-application`** (proto types in the application layer); **commit generated Java** (large diffs,
  a second source of truth); **`buf generate` with remote plugins for Java** (network access in every build).
