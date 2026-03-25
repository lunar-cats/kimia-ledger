# ADR 0025: FX execution, settlement, reconciliation, projection repair, incident freeze, recovery and retention

Status: Proposed · Date: 2026-11-15 · Amends 0007

Replaces earlier drafts: old 0041, old 0029.

## Context
Review §7.3 (`fx_quotes`, settlement and reconciliation tables, `projection_repairs`), §12, §16.2, §16.3; audit
finding R-7; review D-14. ADR 0007 says balances "may be rebuilt from the journal at any time"; a rebuild that
silently overwrites a balance is the threat "balance changed without postings". The first draft (old 0029)
therefore withdrew rebuilding and added an operator freeze table; it predates positions, wallets and the account
fence. Same-day revision: single-use quotes, execution versus valuation rates and the repair audit table follow the
owner's final decisions.

**Owner decisions (2026-11-15), FX:** FX is quote-based; Treasury or an FX provider supplies the quote; Ledger
validates and snapshots it. Quotes are single-use by default and expire. Source and target assets and amounts
are explicit. Fees, spread and rounding policy are recorded; rounding is deterministic and reproducible. Each
asset balances on its own. Clearing and fee/spread postings are explicit. A reversal uses the exact original
execution amounts and rate, never the current market rate. The execution rate is distinct from the accounting
or reporting valuation rate. System accounts may be pooled (ADR 0021 D2).

**Owner decision (2026-11-15), valuation:** distinguish the execution FX rate from the accounting/reporting
valuation rate. A tenant-specific functional/reporting currency is required for consolidated reporting; its
selection is a business/accounting policy and is not guessed. Historical valuation rates are retained so reports
are reproducible as of a date.

## Decision

### D1. FX execution (old 0041)
1. `fx_quotes` is an append-only snapshot: execution `rate` (`NUMERIC(38,18)`, target per source, major units),
   optional mid rate, `rate_source`, `source_ref`, `quoted_at`, `valid_until`, `rounding_mode`, source and target
   amounts, fee (amount, asset) and spread amount.
2. `ExecuteFx` (scope `fx:write`): `now ≤ valid_until` else `FX_QUOTE_EXPIRED` (recorded); `target_amount` equals
   `source_amount × rate` rounded with both asset scales and the recorded `rounding_mode` in `core.fx`
   (`BigDecimal`, explicit `MathContext`, ADR 0015 D3) else `FX_QUOTE_MISMATCH` (not recorded).
3. **Single-use.** `ux_ltx_fx_quote_once` allows one transaction per quote (smoke S16; I-38). A multi-use quote mode
   would need a new ADR.
4. **Postings:** customer source bucket debit; FX clearing (system) credit; FX clearing target debit; customer
   target bucket credit; spread or fee from clearing to an FX revenue system account. Each asset balances (I-4).
5. **Reversal** uses the original execution amounts. Differences against current rates appear only through
   valuation (D4).

### D2. Settlement (old 0041)
1. Customer bucket debit and rail clearing credit; transaction added to a `settlement_batches` batch
   (`OPEN → SUBMITTED → SETTLED | FAILED`). Bank confirmation: `SETTLEMENT` transaction from clearing to the
   settlement bank or nostro system account. Bank rejection: `REVERSAL` or `REFUND`, batch or item `FAILED`.
   Settlement corrections into a `SUSPENDED` wallet are `SYSTEM` origin (ADR 0021 D5).
2. `settled_total` versus items: a difference is a discrepancy, never an edit. One batch per transaction.

### D3. Reconciliation and projection repair (old 0041, old 0029)
1. Inputs: `external_statement_lines` (append-only, SHA-256 of the raw file) and provider data from Payments or
   Operations. Output: `recon_runs`, `recon_discrepancies`. No journal row ever changes.
2. Detect-only, as `ledger_readonly`, windowed and keyset, per tenant; frequency to be set. Checks: position equals
   last `balance_after` and `last_position_seq`; gapless chain (I-10); bucket mapping; nothing on closed wallets or
   buckets; reservations equal active hold remainders (ADR 0023); every transaction balanced per asset; Σ per tenant
   and asset = 0 (I-12); every posting's `account_version` exists in snapshots (backed by a foreign key); every
   `ACCEPTED` idempotency record points to an existing resource.
3. Resolution in `recon_resolutions`; a financial correction is an `ADJUSTMENT` with `approval_ref` (`EXCEPTIONAL`
   origin when a restricted wallet is involved).
4. **Projection repair** (replaces "nothing is rebuilt" of old 0029): never automatic; runbook with human approval:
   block the position with an operational restriction, compute the value from the journal, write a
   `projection_repairs` row with old and new values and `approval_ref`, then update with role `ledger_repair`
   (ADR 0014 D3). If the journal is wrong, post an `ADJUSTMENT` instead. Read models may be rebuilt; the journal
   never is.
5. Drift, sequence gap, unbalanced transaction: severity-1 alerts and incidents. Codes (ADR 0019 D2):
   `FX_QUOTE_EXPIRED`, `FX_QUOTE_MISMATCH`, `FX_QUOTE_USED`, `SETTLEMENT_BATCH_STATE_INVALID`.

### D4. Valuation rates and reporting currency (old 0041)
1. `fx_valuation_rates(rate_source, base_asset, quote_asset, as_of, rate, source_ref)` is append-only per tenant;
   it is never used to execute or reverse a trade.
2. `tenant_settings.reporting_asset_code` and `valuation_policy_ref` are nullable until the business decides;
   while null, Ledger offers no cross-asset totals (per-asset balances only; I-31).
3. A report "as of D" uses, per asset pair, the latest valuation rate with `as_of ≤ D` from the approved source,
   so re-running it gives the same numbers.
4. Revaluation of clearing positions is an `ADJUSTMENT` with `approval_ref` that cites the valuation rate used.

### D5. Incident freeze, recovery and retention (old 0029)
1. **Freeze on a mismatch.** The affected wallets or buckets are frozen locally (`FROZEN` with `reason_code`,
   ADR 0021 D4) or the account is restricted by the Account Service through the fence (ADR 0022 D5). This
   replaces the operator freeze table of old 0029; reads still work.
2. **A mismatch is a severity-1 incident.** Journal rows are never edited; the correction, once the cause is
   known, is new transactions by a human-approved procedure (D3.3, D3.4).
3. **Recovery.** The ledger acknowledges a write only after commit. RPO and RTO are ADR 0014 D5. If a restore ever
   loses acknowledged commits (cross-region), callers resubmit them and the same idempotency keys make that safe;
   the runbook says so.
4. **Retention.** No financial row is deleted until legal retention is decided, idempotency records included.
   Partitioning, if ever needed, is `PARTITION BY HASH (tenant_id)`; time partitions only for outbox, inbox and
   Payments tables. Archival is decided when volume is known.

## Consequences
- The refund ceiling is a Payments policy (ADR 0016 D4).
- Storage grows without bound until retention is decided; the size per transaction is to be measured.
- A repair runbook, the `ledger_repair` role and the incident freeze procedure are needed before production.

## Needs benchmark or human verification
- Reconciliation run time on realistic volumes (ADR 0017, capacity section).
- Accounting review of the FX and settlement posting patterns by finance.

## Open items
- Functional/reporting currency per tenant (owner: finance per tenant; assumption: none; needs: accounting policy).
- Approved valuation policy and rate sources (owner: finance/Treasury).
- Legal retention of quotes and statements (owner: legal/DPO; assumption: never deleted until decided).
- Reconciliation frequency (owner: operations).

## Alternatives rejected
- **Nothing is rebuilt; operator freeze table (old 0029).** With insert-only balances there was no derived table,
  so nothing could be rebuilt; a freeze row writable only by an operator user blocked postings and closing. Lost
  because positions are now a mutable projection (ADR 0017) that needs an audited repair path, and freezes are
  wallet states or account fences. Its rules survive: no automatic rebuild, the journal is never edited, a
  mismatch is a severity-1 incident, recovery by resubmission under the same keys.
- **FX only in the caller (old 0019).** Ledger could not prove the rate used.
- **Settlement as a status change on the original transaction (Sarv).** Mutates journal rows.
- **Automatic projection rebuild.** Hides evidence and may overwrite a correct value with a wrong one.
- **Freeze through a client API scope or a status column for the runtime user (old 0029).** Replaced by the
  approved `wallets:freeze` scope (ADR 0021 D5) and the fence.
