# ADR 0030: Transaction port in ledger-application

Status: Proposed · Date: 2026-11-14

## Context
Review findings D-7, P-3. `ledger-application` may not import Spring or JDBC, yet the use case must run lock,
validate and insert as one transaction (ADR 0020). Without a port, the orchestration moves into the SQL module
and the business rules follow it.

## Decision
1. `ledger-application` defines the port. Names are indicative:

```java
interface LedgerTransactions {
  <T> T inWriteTransaction(TenantId tenant, Deadline deadline, Function<WriteUnitOfWork, T> work);
}

interface WriteUnitOfWork {
  Optional<IdempotencyRecord> findIdempotencyRecord(IdempotencyKey key);
  List<LockedAccount> lockAccounts(List<AccountId> idsInLockOrder);   // I-17 order, distinct
  void appendEntry(Entry entry);                                      // entry, postings, reversal link
  void appendClosure(AccountId account);
  void recordOutcome(IdempotencyRecord record);                       // last statement
}
```

2. The use case orchestrates; `ledger-core` validates; the adapter only executes statements.
3. **Outcomes are values.** A rejection is returned by `work`, and the port commits (the idempotency record
   must persist, ADR 0017). Only failures without an outcome leave the port as typed exceptions defined in
   `ledger-application`: `Contention`, `IdempotencyRace`, `Unavailable`. Each rolls back.
4. `IdempotencyRace` makes the use case read the winner in a new read and return replay or conflict.
5. The adapter in `ledger-persistence-oracle` implements the port with Spring's transaction support and
   `JdbcClient`, applies the deadline as transaction and statement timeouts (ADR 0026), and maps Oracle errors
   to the typed exceptions.
6. Reads use a separate read port without locks.
7. Clock and id generation are ports too (`Clock`, `IdGenerator`), implemented in `ledger-server`.

## Consequences
- The write algorithm of ADR 0020 lives in one use case and is testable with a fake unit of work.
- An adapter cannot reorder the steps; it has no access to the validation.
- No checked exceptions cross the module boundary; no generic `RuntimeException` either.

## Alternatives rejected
- **Spring `@Transactional` on the use case.** Framework import in `ledger-application`.
- **Repository methods that each open their own transaction.** Breaks atomicity.
- **The whole write as one adapter call (`postEntry(command)`).** Moves orchestration and the rule order into
  the SQL module.

## Relation to the review
Agrees with D-7 and P-3. **Differs** on the shape: no claim-first method (ADR 0017), outcome recorded last,
typed failures named.
