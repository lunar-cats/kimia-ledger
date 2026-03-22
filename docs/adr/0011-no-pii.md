# ADR 0011: No personal data in the ledger

Status: Accepted · Date: 2026-11-13

## Context
The journal is immutable and long-lived. Personal data inside it could never be erased, which conflicts with
privacy law and with the append-only decision.

## Decision
- The ledger stores **no PII**. Accounts and entries carry only an opaque `external_ref` chosen by the caller
  (max 255 chars) and free-form `metadata` limited in size and documented as "must not contain personal data".
- Logs never include request bodies, `external_ref`, or `metadata`.
- The threat model treats PII in `external_ref` as caller misuse; the integration guide says so prominently.

## Consequences
- Erasure requests are handled in the caller's systems by breaking the link to `external_ref`.
- The ledger can be replicated, archived and audited without privacy review of its content.
