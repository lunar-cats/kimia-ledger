# ADR 0006: Money as integer minor units plus ISO-4217 currency

Status: Accepted · Date: 2026-11-13

## Context
Binary floating point cannot represent decimal money exactly. Rounding differences in a ledger are money
created or destroyed.

## Decision
- Amounts are **integers in the currency's minor unit** (cents, satoshi-free: fiat only in v1).
  Java: `long`; Oracle: `NUMBER(38,0)`; wire: `int64` or decimal `string`.
- Currency is a 3-letter **ISO-4217** code; minor-unit exponent comes from ISO-4217, not from the caller.
- **Never** `float`, `double`, or `BigDecimal` with non-zero scale. `BigDecimal(scale 0)` is allowed only at the JDBC boundary.
- No arithmetic outside `Money`; overflow throws, never wraps (`Math.addExact`).

## Consequences
- Display formatting (decimal point) is a client concern.
- Currencies with unusual exponents (JPY 0, BHD 3) work without special casing.
- Static analysis (Error Prone / ArchUnit) will forbid floating-point types in core.
