# ADR 0015: Money and assets: NUMERIC(38,0) and BigInteger, per-asset immutable scale, decimal strings on the wire

Status: Proposed · Date: 2026-11-15 · Amends 0002 (money columns), 0006

Replaces earlier drafts: old 0034; old 0019 (superseded, see Alternatives).

## Context
Review §7.2, §7.3 (`assets`, `fx_quotes`, `fx_valuation_rates`), §12.1; review finding B-9; audit findings R-9,
R-20.

**Owner decision (2026-11-15):** the asset list covers fiat, crypto, tokens and stocks, scale up to 18. Money
is `NUMERIC(38,0)` in PostgreSQL and `BigInteger` in Java, never `float` or `double`. Amounts on the wire are
decimal strings. (Same-day revision: asset kinds widened to the owner's list; the asset list is configuration,
not an open design question.)

`bigint` holds ±9.22 × 10^18 minor units, about 9.22 major units at scale 18; changing a column type later on
a large append-only table rewrites the journal. The earlier draft (old 0019) chose `int64` and ISO-4217 for fiat
only.

## Decision

### D1. One money type end to end (old 0034)
1. Every amount and balance column is `NUMERIC(38,0)` (postings, positions, holds, FX amounts, settlement,
   statements, `max_amount`, overdraft limits).
2. `ledger-core` has an immutable `Amount` over `BigInteger` with exact arithmetic. `float`, `double` and
   `BigDecimal` are forbidden for money amounts.
3. Wire (`ledger.v1`, `ledger.events.v1`): decimal strings; positive amounts `[1-9][0-9]{0,37}`, signed balances
   with an optional leading `-`; no fraction, exponent or leading zero. `int64` money fields are removed
   (breaking, allowed before `v1.0.0`, ADR 0019 D3).

### D2. Assets with an immutable scale (old 0034)
1. `assets(asset_code, asset_kind, scale, max_amount)` is append-only. `asset_kind ∈ {FIAT, CRYPTO, TOKEN,
   STOCK, OTHER}`. `scale` 0..18 never changes; a new scale is a new code. Rows are seeded by migration; the
   seed list is configuration approved by the owner before production.
2. `assets.max_amount` bounds each posting amount. A balance outside the column range →
   `BALANCE_LIMIT_EXCEEDED` (recorded), checked in `ledger-core`.
3. Rounding of amounts is the caller's or `core.fx`'s job (D3); the ledger only adds integers.

### D3. Per-asset sums and FX rates (old 0034)
1. I-4 sums per `asset_code`. Unlike assets are never added together; any cross-asset total needs a valuation
   rate and a reporting currency (ADR 0025 D4).
2. FX rates are exact decimals (`NUMERIC(38,18)`), used only in `core.fx` with `BigDecimal`, an explicit
   `MathContext` and a recorded rounding mode; results are integers before they reach a posting (ADR 0025 D1).

## Consequences
- One money type end to end. Clients parse big integers from strings; display uses the asset scale.
- ISO-4217 `CHAR(3)` currency and the per-account exponent of old 0019 are gone.
- `CLAUDE.md` money rule: `NUMERIC(38,0)` / `BigInteger` + asset code; `BigDecimal` only for FX rates in `core.fx`.
- Proto follow-up: `Money { asset_code, amount (string) }`.
- Ordinary overdraft (ADR 0022 D8) is not permission for crypto short-selling or securities margin; those need
  borrowing, collateral and exposure policies and are out of scope.

## Needs benchmark or human verification
- `NUMERIC` versus `bigint` arithmetic and index cost: benchmark (ADR 0017, capacity section).
- Golden tests for the decimal-string format and its idempotency-hash encoding (ADR 0018 D2).

## Open items
- The concrete seed list of assets is configuration, approved by the owner before production (D2.1).

## Alternatives rejected
- **`int64` money with ISO-4217 currency (old 0019).** One range `1 .. 2^63−1` for postings and
  `−2^63 .. 2^63−1` for balances, `NUMBER(19,0)` columns with `CHECK`, a 12-character currency column with a
  foreign key to an ISO-4217 currency table, the exponent copied per account at creation, FX as four postings with
  the rate and rounding in the caller. Lost because `bigint` is too small for scale-18 assets (owner decision
  2026-11-15) and an exponent per account is replaced by an immutable scale per asset code. Its useful points
  survive: balance overflow is its own recorded code (`BALANCE_LIMIT_EXCEEDED`), and a changed scale never
  rescales history.
- **Fractional `NUMERIC` amounts.** One column cannot fit scales 0 to 18.
- **Floating point for money.** Not exact.
- **FX only in the caller (old 0019).** Ledger could not prove the rate used (ADR 0025 D1).
