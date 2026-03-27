# Contracts

`proto/ledger/v1/*.proto` is the source of truth for the Kimia Ledger API (ADR 0004). REST is served by a hand-written
adapter that conforms to the `google.api.http` annotations (ADR 0019 D1), not a transcoding runtime. `openapi/openapi.yaml` is **generated** from the protos
and committed; never edit it by hand.

```
buf.yaml            module config: deps (googleapis, pinned commit), lint (STANDARD + COMMENTS), breaking (FILE)
buf.lock            resolved dependency digests
buf.gen.yaml        OpenAPI generation (remote plugin community/google-gnostic-openapi, pinned)
proto/ledger/v1/    target set (pending rewrite): money, error, transaction, wallet, hold, fx, settlement,
                   ledger_service
proto/ledger/events/v1/  event envelope and topic events (ADR 0024); pending
openapi/            generated OpenAPI 3.0 document
```

**Status.** The files in `proto/ledger/v1/` are still the old draft: `account.proto`, `entry.proto`, an `int64`
`Money`, and `CreateAccount` and `GetAccount` in `ledger_service.proto`. Do not build clients against them. They are
being rewritten to the target set above (review §13.1; ADRs 0015, 0019, 0020, 0021). The file names in the
target set follow review §13.1 and may change. `openapi/openapi.yaml` is regenerated after the rewrite.

The ledger has no account API. Accounts belong to the external Account Service (ADR 0021 D1). The fence service
(`ApplyAccountState`) is internal and is kept apart from the public services (review §13.1).

## Events

- Events use the `ledger.events.v1` package in `proto/ledger/events/v1/` (ADR 0024 D2, D6). Each event is one `EventEnvelope` message. It is stored serialized in the outbox payload and sent as the Kafka record value; the record key is `message_key`. Fields:
  - required: `event_id` (UUID), `event_type`, `schema_version`, `tenant_id`, `aggregate_type` (TRANSACTION, WALLET, BUCKET, HOLD, SETTLEMENT_BATCH or RECONCILIATION_RUN), `aggregate_id`, `aggregate_sequence` (≥ 1), `occurred_at` (UTC, microseconds, set by the producing transaction), `correlation_id` (never empty), `producer` (enum `Producer`, v1: `PRODUCER_LEDGER`), `payload` (bytes; the business payload is not defined yet).
  - optional: `causation_id` (the `event_id` of the causing event) and `trace_parent` (W3C, no business meaning).
  The envelope never changes after insert: re-sends send the same bytes. A change of meaning needs a new `schema_version` or event type. No PII, secrets, amounts or statuses in the envelope. `producer` is not an authorisation claim: consumers must not use it to authorise anything; topic ACLs decide who may produce to `ledger.*`. The retention values are proposals; their approvers are listed in ADR 0024 D7.7.
- Each event also carries `aggregate_type`, `aggregate_id` and `aggregate_sequence` (≥ 1, unique per aggregate). Sequences are required in v1 (I-41).
- Delivery is at least once. Consumers de-duplicate on `event_id` (ADR 0024 D3).
- Order is promised per aggregate and never globally: transactions, wallets, buckets, holds, settlement batches and reconciliation runs. Each aggregate's events share one ordering key, `message_key` = `{tenant_id}:{aggregate_id}` (I-40). Events with different keys have no order.
- Consumers keep the last `aggregate_sequence` per aggregate. Equal or lower: ignore. Last + 1: apply. Higher: hold for a bounded wait, then resync from the read API. Read responses must return `event_sequence` (contract follow-up).
- A quarantined event is a permanent gap for its aggregate (I-42). The consumer resyncs from the read API.
- Settlement batch events share one sequence per batch: `SettlementOpened` is 1 (written when the batch is created), then `SettlementBatchSubmitted`, `SettlementCompleted` and `SettlementFailed`.
- Transaction events: `TransactionPosted` is 1, `FxExecuted` is 2 (FX only), and `TransactionReversed` takes the next value (2, or 3 for an FX original).
- Retention notation (proposals): Kafka <= outbox <= inbox, `7 <= 14 <= 30` days. The inbox must exceed the outbox by at least the maximum consumer lag.
- Events stay in Kafka for at least 7 days. Consumers keep their de-duplication memory longer than the longest re-delivery they can receive: Ledger re-sends within `outbox_retention` (proposal 14 days); recommended inbox retention is 30 days.
- Ledger's outbox keeps published events for `outbox_retention` (proposal: 14 days). A re-send of an older event is possible only within that window, by operator request, with the original `event_id` (ADR 0024 D7).
- After `outbox_retention`, rebuilding from the list APIs and the gapless `position_seq` is a recovery and reporting rebuild, not audit evidence (ADR 0024 D1, D7).

## Commands (run in this directory)

```bash
buf lint                        # style + comment rules
buf format -w                   # format in place (CI uses -d --exit-code)
buf generate                    # regenerate openapi/openapi.yaml
buf breaking --against '../.git#branch=main,subdir=contracts'   # no breaking change inside ledger.v1
```

## Rules

- Until `v1.0.0`, `ledger.v1` is a draft and may change in any way. `buf breaking` runs but does not block
  (ADR 0019 D3).
- From `v1.0.0`, changes inside `ledger.v1` are additive only: new fields, new enum values, new RPCs.
  Never renumber, remove,
  or change the type of a field; never change an HTTP path. A breaking change is a new package `ledger.v2`.
- Every message, field, enum value and RPC has a comment (lint enforces it); comments cite the invariant (I-n) or ADR they implement.
- Amounts are decimal strings of integer minor units, with an asset code: `ledger.v1.Money { asset_code, amount }`
  (ADR 0015 D1, follow-up). Positive amounts match `[1-9][0-9]{0,37}`; balances may start with `-`. Never floating
  point. Each asset code has an immutable scale, 0 to 18 (ADR 0015 D2).
- There is no tenant field; the tenant comes from the token (I-19).
- Each release publishes this directory's protos (with their googleapis imports) and the OpenAPI file as a versioned artifact (ADR 0012).

## Error code names

Use these canonical names in docs, code and tests (ADR 0019 D2). The names below have no proto prefix. In the proto enum they take the `ERROR_CODE_` prefix.

| Canonical name | Replaces | Meaning |
|---|---|---|
| `ACCOUNT_UNKNOWN` | `ACCOUNT_NOT_FOUND` | The account id does not resolve in the tenant (I-6). |
| `TRANSACTION_NOT_FOUND` | `ENTRY_NOT_FOUND` | The transaction to reverse does not exist (I-14). |
| `TRANSACTION_ALREADY_REVERSED` | `ENTRY_ALREADY_REVERSED` | The transaction was reversed before (I-14). |
| `TRANSACTION_NOT_REVERSIBLE` | `ENTRY_NOT_REVERSIBLE` | The transaction is itself a reversal (I-14). |
| `ASSET_MISMATCH` | `CURRENCY_MISMATCH` | The posting asset differs from the asset of its position (I-5). |
| `ACCOUNT_RESTRICTED` | `ACCOUNT_CLOSED`, `ACCOUNT_FROZEN` | The account is closed, frozen or lacks the capability needed (I-28). |
| `WALLET_FROZEN` | (new name) | The wallet or bucket is frozen (I-7, I-37). |

- `INVALID_ENTRY`, `UNBALANCED_ENTRY` and `TOO_MANY_POSTINGS` keep their names.
- The draft protos in `proto/ledger/v1/` still carry the old names (for example `ERROR_CODE_ACCOUNT_NOT_FOUND` in `error.proto`). The rewrite renames them. Do not build clients on the old names.
- Until `v1.0.0` the draft may rename values (ADR 0019 D3). `buf breaking` reports the rename but does not block it before `v1.0.0`. From `v1.0.0` a rename is a breaking change.
