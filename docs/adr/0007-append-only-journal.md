# ADR 0007: Append-only journal; corrections are reversing entries

Status: Accepted · Date: 2026-11-13

## Context
A ledger is evidence. Any ability to edit or delete a posting destroys auditability and enables fraud.

## Decision
- Postings and entries are **never updated or deleted**, by code or by database grant.
- A mistake is corrected by posting a **reversing entry** that references the original (`reverses_entry_id`),
  followed by the correct entry.
- The runtime DB user has **INSERT and SELECT only** on journal tables. Migrations run as a different user.
- The journal tables are planned as **Oracle Immutable (or Blockchain) tables** once the schema stabilises.

## Consequences
- History is complete and reproducible: any balance equals the sum of its postings at any point in time.
- Storage grows monotonically; archival is a read-side concern, never a delete.
- Balance snapshots are derived data and may be rebuilt from the journal at any time.
