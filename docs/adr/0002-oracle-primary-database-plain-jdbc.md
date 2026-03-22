# ADR 0002: Oracle as primary database, plain JDBC, Flyway, no jOOQ

Status: Accepted · Date: 2026-11-13

## Context
The first production deployment runs on Oracle. We want the option of Oracle Immutable/Blockchain tables for
the journal. jOOQ requires a commercial licence for Oracle editions, which conflicts with an Apache-2.0 project.

## Decision
- **Oracle** is the primary and only supported database in v1. Dev and tests use **Oracle AI Database Free**
  via Testcontainers (`gvenzl/oracle-free`, slim variant).
- **All SQL lives in `ledger-persistence-oracle`.** Core and application modules know nothing about SQL
  (enforced by ArchUnit).
- **Plain JDBC via Spring `JdbcClient`**, bind parameters only. **No jOOQ**, no JPA/Hibernate.
- **Flyway** (`flyway-database-oracle`) for migrations, run by a separate migration DB user.
- Money columns are `NUMBER(38,0)`; currency is `CHAR(3)`.

## Consequences
- A second database later means a second persistence module, not changes to core.
- Hand-written SQL needs integration tests against real Oracle; unit tests cannot replace them.
- No ORM means no accidental UPDATEs on journal rows; the append-only rule is also enforced by DB grants.
- Testcontainers makes Docker a hard requirement for the full test suite and for CI.
