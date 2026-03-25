# ADR 0024: Events: transactional outbox and inbox on Kafka; topics, keys, per-key order, at-least-once, single relay

Status: Proposed · Date: 2026-11-15 · Amends 0003, 0008, 0013 · Relates to 0014, 0015, 0016, 0017, 0019, 0021, 0022, 0023, 0025, 0026

Replaces earlier drafts: old 0040; old 0021 (superseded, see Alternatives).

## Context
Review §5.3, §7.3 (`outbox_events`, `inbox_events`), §14, §16.4, §19 (old 0021 rejected; its claim-by-status rule
kept); review finding D-1; audit finding R-1. Owner baseline: Kafka 4.x, transactional outbox and inbox.
Consumers: Payments and the Operations read model consume Ledger events; Ledger consumes `AccountStateChanged`
(ADR 0022 D2). Same-day revision: the first draft left Kafka settings, schema registry, the activity topic and
inbox retention open; they are decided here as proposals with their reasons, for the owner to accept with this ADR.

Versions (review §5.3): kafka-clients 4.2.2 in the Spring Boot 4.1.2 BOM (verified); Spring for Apache Kafka
4.1.2 paired in the BOM, compatibility inferred, not tested; Kafka 4.x clients need broker 2.1+ (KIP-896).

## Decision

### D1. Outbox, topics and keys
1. **Outbox** rows are inserted in the same transaction as the state change (ADR 0017 D5 step 15; I-16).
2. Topics and keys:

| Topic | Key | Events | Order |
|---|---|---|---|
| `ledger.transactions.v1` | `{tenant_id}:{transaction_id}` | `TransactionPosted`, `TransactionReversed`, `FxExecuted` | none across transactions |
| `ledger.wallets.v1` | `{tenant_id}:{wallet_id}` | `WalletProvisioningStarted`, `WalletActivated`, `WalletProvisioningFailed`, `WalletStatusChanged`, `WalletClosed`, `BucketCreated`, `BucketStatusChanged` | per wallet |
| `ledger.holds.v1` | `{tenant_id}:{hold_id}` | `HoldCreated`, `HoldCaptured`, `HoldReleased`, `HoldExpired` | per hold |
| `ledger.settlement.v1` | `{tenant_id}:{batch_id}` | `SettlementOpened`, `SettlementBatchSubmitted`, `SettlementCompleted`, `SettlementFailed` | per batch |
| `ledger.reconciliation.v1` | `{tenant_id}:{run_id}` | `ReconciliationCompleted`, `DiscrepancyDetected` | per run |
| consumed `accounts.lifecycle.v1` | `{tenant}:{account_id}` | `AccountStateChanged` | per account, plus `account_version` |

**Ordering key and ordering aggregate.** The ordering key of an event is its Kafka record key, stored as
`outbox_events.message_key` and always `{tenant_id}:{aggregate_id}` (a CHECK enforces it). The *ordering aggregate* is
the entity whose events are ordered. The guarantee is per aggregate, never global (I-40). The table defines both for
every Ledger-owned event of the list above. No event type is added here.

| Event | Topic | Kafka key | Ordering aggregate | Why this aggregate defines the order | Aggregate sequence |
|---|---|---|---|---|---|
| TransactionPosted | ledger.transactions.v1 | `{tenant}:{transaction_id}` | Transaction | A transaction is immutable and written by one database transaction, so it has no concurrent writers. Every transaction emits one, including reversals and hold captures (inference: a gapless position chain, ADR 0017 D2, needs every posting in an event). Balance order is per position and travels inside the event as `position_seq` per posting. A wallet or tenant key is not possible: a transaction touches two or more positions, possibly of different wallets, and a posting does not take the wallet lock, so such a key would not be serialised. | `1` |
| FxExecuted | ledger.transactions.v1 | `{tenant}:{FX transaction_id}` | Transaction | Written in the same database transaction as that transaction's TransactionPosted | `2` |
| TransactionReversed | ledger.transactions.v1 | `{tenant}:{original transaction_id}` | Transaction (the original) | A reversal is created only after the original has committed, and at most once (`ux_ltx_single_reversal`, ADR 0016 D3.4), so the key carries Posted, then Reversed, in causal order. The reversal's own postings travel in its own TransactionPosted. | `2, or 3 for an FX original` |
| WalletProvisioningStarted, WalletActivated, WalletProvisioningFailed, WalletStatusChanged, WalletClosed | ledger.wallets.v1 | `{tenant}:{wallet_id}` | Wallet | Each is one row of `wallet_status_events`, written by a transaction that holds the wallet lock ② (ADR 0021 D3, D4.6) | `wallet_status_events.event_seq` |
| BucketCreated, BucketStatusChanged | ledger.wallets.v1 | `{tenant}:{bucket_id}` | Bucket | Each is one row of `bucket_status_events`. A bucket also changes without the wallet lock (an account seal closes it under its position lock, ADR 0022 D6), so a wallet key would not be serialised; the bucket row is, because creation inserts it and every status change updates it. | `bucket_status_events.event_seq` |
| HoldCreated, HoldCaptured, HoldReleased, HoldExpired | ledger.holds.v1 | `{tenant}:{hold_id}` | Hold | Each is one row of `hold_events`, written under the hold lock ④ (ADR 0023 D3) | `hold_events.event_seq` |
| SettlementOpened, SettlementBatchSubmitted, SettlementCompleted, SettlementFailed | ledger.settlement.v1 | `{tenant}:{batch_id}` | Settlement batch | Each is one row of `settlement_batch_events`, written under the batch lock ⑤ | `settlement_batch_events.event_seq` |
| ReconciliationCompleted, DiscrepancyDetected | ledger.reconciliation.v1 | `{tenant}:{run_id}` | Reconciliation run | One worker produces the events of a run, serially | `recon_runs.event_seq` |
| AccountStateChanged (consumed, not Ledger-owned) | accounts.lifecycle.v1 | `{tenant}:{account_id}` | Account (Account Service) | `account_version` is monotonic per account (ADR 0022 D2) | `account_version` |

Cross-aggregate order is never promised. A wallet and its buckets are different aggregates; a consumer that needs a
bucket's effective state combines the latest event of each (ADR 0021 D4.3). An event type added later must be added to
this table with its key, aggregate and the lock or rule that serialises its producers. One that cannot name them may
not promise order.

### D2. Envelope
**Envelope v1: the minimum, immutable fields.** `contracts/proto/ledger/events/v1` defines one message,
`EventEnvelope`. It is the Kafka record value; the record key is `message_key` (D1). `outbox_events.payload` stores
the serialized envelope. This ADR defines the envelope only. The business payload of each event type is not defined
here (open item); the envelope is closed for v1.

| Field | Type | Required | Rule |
|---|---|---|---|
| `event_id` | UUID (string) | yes | Unique. Generated when the outbox row is written (`outbox_events.event_id`). The same value on every re-send, re-publish and replay. It is the dedupe key of the inbox (D4). |
| `event_type` | string | yes | An event name from the table in D1, for example `HoldCreated`. Names are only appended. |
| `schema_version` | uint32 | yes | Version of the business payload of this `event_type`. Starts at 1. |
| `tenant_id` | UUID (string) | yes | Equal to `outbox_events.tenant_id` and to the tenant part of the key. |
| `aggregate_type` | string | yes | One of `TRANSACTION`, `WALLET`, `BUCKET`, `HOLD`, `SETTLEMENT_BATCH`, `RECONCILIATION_RUN`. |
| `aggregate_id` | string | yes | The id of the aggregate. With the tenant it forms the Kafka key `{tenant_id}:{aggregate_id}` (database CHECK). |
| `aggregate_sequence` | uint64 | yes | At least 1. The sequence defined below. |
| `occurred_at` | timestamp | yes | UTC, microseconds. The instant of the state change, read from the application clock inside the producing database transaction (ADR 0026 D1). Not the instant of publishing. |
| `correlation_id` | string | yes | The `x-correlation-id` of the request that caused the event (ADR 0019 D2.3). A worker that acts without a request (hold expiry, reconciliation) generates one. Never empty. |
| `producer` | `Producer` (enum) | yes | A closed vocabulary. In v1 the only value is `PRODUCER_LEDGER`, the Ledger service; the zero value `PRODUCER_UNSPECIFIED` is invalid and a message that carries it is rejected. It names no host, instance or version. Values are only appended, by an ADR change. It is not an authorisation claim: consumers do not use it to authorise, and only the Ledger identity may produce to `ledger.*` topics (topic ACL, a Kafka configuration decision, same principle as ADR 0022 for `accounts.lifecycle.v1`; inference). |
| `causation_id` | UUID (string) | no | The `event_id` of the event that caused this one, when there is one. Empty otherwise. |
| `trace_parent` | string | no | W3C trace context, for observability only. No business meaning. |
| `payload` | bytes | yes | The business payload of the event type. Not defined here. |

**Immutability.** An envelope is set once, when the outbox row is inserted, and never changes. Re-send, re-publish,
replay and quarantine send or keep the same bytes. Only four delivery columns of the outbox row change, and only by
the relay (D7.1; ADR 0014 D3.1); the runtime role can only insert. A change of the meaning of a field is a new
`schema_version` or a new event type. After `v1.0.0`, fields are only added, as optional (ADR 0019 D3). The envelope
holds no personal data, no secrets and no amounts or statuses; those belong to the payload.

No PII. Amounts are decimal strings (ADR 0015 D1). There is no tenant-wide order; per-position order is
`position_seq` (ADR 0017 D2).

**Aggregate sequence (required in v1).**
1. Every Ledger-owned event carries `aggregate_type`, `aggregate_id` and `aggregate_sequence` (≥ 1). The outbox row
   stores the same three values under a unique key `(tenant_id, aggregate_type, aggregate_id, aggregate_sequence)`.
2. For Wallet, Bucket, Hold and Settlement batch, the sequence is the `event_seq` of the history row that the event
   reports. Every such history row is emitted as exactly one event, in the same database transaction, and the event
   name follows the row (`to_status` and reason, or `event_type`). The history tables already provide it: primary key
   `(tenant_id, aggregate_id, event_seq)`, assigned under the aggregate's lock. A batch's creation writes the first
   history row (`from_status` NULL, `to_status` OPEN) and emits `SettlementOpened`, sequence 1. Mapping of history
   rows to events: Settlement batch `OPEN` → SettlementOpened, `SUBMITTED` → SettlementBatchSubmitted, `SETTLED` →
   SettlementCompleted, `FAILED` → SettlementFailed. Wallet: the first row (NULL → PROVISIONING) →
   WalletProvisioningStarted; PROVISIONING → ACTIVE → WalletActivated; → CLOSED with reason PROVISIONING_FAILED →
   WalletProvisioningFailed; any other → CLOSED → WalletClosed; every other row → WalletStatusChanged. Bucket: the
   first row (NULL → ACTIVE) → BucketCreated; every other row → BucketStatusChanged. Hold: `event_type` (CREATED,
   CAPTURED, RELEASED, EXPIRED) → HoldCreated, HoldCaptured, HoldReleased, HoldExpired. For a reconciliation run it is
   `recon_runs.event_seq`, incremented by the single worker. For a Transaction it is a deterministic sequence:
   `TransactionPosted` = 1, `FxExecuted` = 2 (FX transactions only), `TransactionReversed` = the next number (2, or 3
   for an FX original). It is a real sequence: monotonic and gapless per transaction, with no artificial gap for
   non-FX transactions. The outbox unique key and `ux_ltx_single_reversal` (a transaction is reversed at most once)
   make a duplicate number impossible. A stored counter is neither possible nor needed: `ledger_transactions` is
   append-only and cannot hold one, the three events have no concurrent writers, and a separate history table would
   only repeat these numbers (inference; a `transaction_events` table can be added later without changing the wire
   format). No other table gets a counter.
3. The sequence is monotonic and gapless per aggregate in the outbox. A consumer sees a gap only if an event was
   quarantined (D7.12), was lost in a restore, or aged out of every buffer.
4. **Gap detection by the consumer.** Keep the last applied sequence per `(aggregate_type, aggregate_id)`. Equal or
   lower: a duplicate or a stale event; ignore it (the inbox also de-duplicates on `event_id`). Exactly last + 1:
   apply. Higher than last + 1: do not apply; hold it for a bounded wait set by the consumer (a missing event may
   still be in flight after a re-publish or a partition change), then resync that aggregate from the Ledger read API
   and continue above the sequence the read returned.
5. `TransactionPosted` also carries `position_seq` per posting. A position has no events of its own; a consumer that
   tracks balances applies the same rules to `(position, position_seq)` and resyncs with `ListBucketPostings` or
   `ListAccountPostings` (D4.1).
6. This is required in v1 for every event in D1. Contract follow-up: the read messages for Wallet, Bucket, Hold,
   Settlement batch and Reconciliation run must return the aggregate's current `event_sequence`, so a resync has a
   baseline (not yet in the protos). A consumer that starts without state loads from the API first and then applies
   events above the returned sequence.
7. **Missing Transaction events.** A consumer detects a missing lower event when a higher one arrives: a
   `TransactionReversed` with sequence 3 without sequence 2 (FX original), or any `TransactionReversed` without a
   `TransactionPosted`. The last event of a transaction cannot be detected from this stream alone, as with every
   aggregate: a consumer cannot tell 'not reversed yet' from 'lost'. Reversals are covered otherwise: the reversal is
   itself a transaction with its own `TransactionPosted` (type REVERSAL), whose postings carry a gapless
   `position_seq` (ADR 0017 D2), and the read API returns `reversed_by_transaction_id` (ADR 0016 D3.2; derived on
   read, not yet in the protos). A consumer must not rely on `TransactionReversed` alone to learn about a reversal.
8. **Who writes Transaction events, and what protects their sequence.** The sequence of item 2 rests on one
   assumption: no two writers produce events for the same transaction at the same time.
   - *Domain invariant (I-41).* For one transaction T: `TransactionPosted(T)` has sequence 1. If T is an FX
     transaction, `FxExecuted(T)` has sequence 2. `TransactionReversed` for T has sequence 2 if T is not an FX
     transaction and 3 if it is, and T has at most one. Each of these exists at most once, and nothing else carries a
     Transaction sequence.
   - *Writers.* Only the Ledger write path creates Transaction events, and only in the same database transaction that
     writes the journal row. `PostTransaction`, and the use cases that build on it (hold capture, settlement, FX,
     adjustment), write `TransactionPosted` (and `FxExecuted` for an FX transaction) together with the new
     `ledger_transactions` row. `ReverseTransaction` writes the reversal's own `TransactionPosted` and the original's
     `TransactionReversed`. No relay, worker, consumer or client writes them. Only the runtime role `ledger_app` can
     insert outbox rows (ADR 0014 D3.1).
   - *Serialisation in the application.* The first two events are written once, by the transaction that creates the
     row. `TransactionReversed` is written only by `ReverseTransaction`, which locks the original's positions in the
     global order and checks 'already reversed' under those locks (ADR 0016 D3.5, ADR 0017 D4), so two concurrent
     reversals of one original are serialised. It reads the original's immutable `transaction_type` to choose 2 or 3.
- *Final protection in the database.* The partial unique index `ux_ltx_single_reversal` allows one reversal per
  original for as long as the journal exists, and the `CHECK`s of `ledger_transactions` tie `REVERSAL` to
  `reverses_transaction_id` (ADR 0016 D1.1, D3.4). The unique key `(tenant_id, aggregate_type, aggregate_id,
  aggregate_sequence)` on the outbox rejects a duplicate number only while the outbox rows exist; the cleanup job
  removes published rows after `outbox_retention` (D7.6), so it is not the permanent guard. **Database constraints
  alone do not enforce sequence correctness.** The unique keys stop a duplicate number (the outbox key only while its
  rows exist) and a second reversal, but nothing in the database checks that a number equals the value of the
  invariant above. That rule belongs to the domain layer (CLAUDE.md): `ledger-core` computes it as a pure function of
  the transaction type and the event type, citing I-41, and a property test covers it; the reconstruction test (D7.8)
  checks it again against the retained tables.
   - *Limit.* If the application ever had a second writer for the same transaction, the sequence would no longer be
     guaranteed. No event store is introduced.

### D3. Delivery: at-least-once, ordering key and a single relay
1. No exactly-once claim across PostgreSQL and Kafka. Producer `acks=all`, `enable.idempotence=true`. Consumers
   de-duplicate on `event_id`.
2. **Ordering key, single relay, per-key blocking.**
   - *Invariant (I-40):* events sharing an ordering key are published in `outbox_id` order; events with different
     ordering keys never block each other; the guarantee is per aggregate, not global. A quarantined event (D7.12) is
     the only exception: it is skipped, recorded and never sent by the relay.
   - `outbox_id` is an order only within one key. Across keys it promises nothing (no global order, ADR 0017 D2.2).
     Within a key it equals commit order, because the transactions that produce the key's events are serialised by the
     entity's row lock (D1) and the identity value is drawn inside that transaction.
   - A pending row is *eligible* when it has no quarantine record and no earlier row (lower `outbox_id`) with the same
     `message_key` is still pending without a quarantine record; it is then the *head* of its key. Only heads are
     sent. The next row of a key becomes the head once the previous one is marked published. The selection below was
     tested for these semantics on PostgreSQL 18.7 (2026-11-15); its performance was not measured.
     ```sql
     SELECT o.outbox_id
     FROM outbox_events o
     WHERE o.published_at IS NULL
       AND NOT EXISTS (SELECT 1 FROM outbox_quarantine q WHERE q.outbox_id = o.outbox_id)
       AND NOT EXISTS (SELECT 1 FROM outbox_events p
                       WHERE p.message_key = o.message_key AND p.outbox_id < o.outbox_id AND p.published_at IS NULL
                         AND NOT EXISTS (SELECT 1 FROM outbox_quarantine pq WHERE pq.outbox_id = p.outbox_id))
     ORDER BY o.outbox_id LIMIT :batch;
     ```
   - One relay is active at a time (leader chosen by advisory lock). Each poll selects the heads, at most one row per
     key, in `outbox_id` order; sends them (heads of different keys may be in flight together, a key has at most one
     record in flight); and marks each row published after its ack (D7). A blocked key contributes one row per poll,
     so it cannot starve other keys. The relay selects by status, not by a cursor, so a late commit is picked up by a
     later poll.
   - A failure blocks its own key only (D7.3).
   - Kafka orders records per partition. The default partitioner maps a key to one partition; changing a topic's
     partition count changes that mapping and can reorder events across the change, so it is a planned operation done
     after the topic's outbox rows are drained (inference, not verified for the chosen Kafka version).
   - The relay marks a row published only after the broker acknowledges it and never deletes. Old published rows are
     removed by a separate cleanup job (D7).

### D4. Inbox and poison messages
1. `(consumer_name, message_id)` inserted with the effect; offset committed after the database commit. A
   `position_seq` gap → resync from `ListBucketPostings` or `ListAccountPostings`. Inbox rows are pruned by the
   cleanup job (D7.10).
2. A consumer that cannot apply a message after bounded retries parks it in `{topic}.dlq.{consumer}` and raises an
   alert; it never skips silently.

### D5. Cluster settings, schema registry, activity topic (proposals)
1. Replication factor 3, `min.insync.replicas=2`, so an acknowledged event survives one broker loss; topic
   retention at least 7 days; outbox and inbox retention follow the chain in D7.7. Values follow the
   durability domain of ADR 0014 D5.
2. **No schema registry in v1.** The event schemas are the protobuf files in `contracts/`, gated by
   `buf breaking` (ADR 0019 D3). A registry can be added later without changing the wire format.
3. **No `ledger.position-activity.v1` in v1.** `TransactionPosted` carries per-posting `position_seq`; consumers
   needing every position change read the gapless list APIs. Adding the topic later is additive.

### D6. Module
`ledger-messaging-kafka`; `org.apache.kafka..` only there. `ledger-contracts` holds the generated `ledger.events.v1`
stubs (ADR 0026 D2); `ledger-messaging-kafka` depends on it for the envelope and payload types.

### D7. Outbox and inbox lifecycle: publish, retention, cleanup, replay
Added 2026-11-15. Values marked (proposal) are for the owner to accept with this ADR. Reasoning marked (inference) is
not verified.

1. **States.** An outbox row is *pending* while `published_at IS NULL` and *published* afterwards. The relay sets
   `published_at` once, and only after the broker has acknowledged the record (`acks=all`). A trigger refuses any
   later change of `published_at` and any DELETE of a pending row. Replay never resets it.
2. **Publish path (`ledger_relay`).** Select the heads of the keys (D3.2) and send them. After the ack, set
   `published_at` and add one to `publish_attempts`, in a short transaction of its own. The relay has no DELETE
   privilege and never deletes. On the first failed attempt of a row it also sets `first_failed_at`.
3. **Publish fails** (broker unavailable, timeout, not enough in-sync replicas, record too large, authorization): the
   row stays pending and is retried. The relay adds one to `publish_attempts`, stores an error class name in
   `last_error` (a database CHECK allows nothing else, D7.12) and retries with capped exponential backoff (values are
   configuration, kept in the relay's memory per key). The failure **blocks its key only**: later rows with the same
   `message_key` are not eligible until the head is acknowledged and marked (I-40), and rows of other keys are sent as
   normal. No row is skipped, reordered, parked or deleted automatically. If the broker itself is down, every key
   fails for that reason; that is an outage, not keys blocking each other.
3a. **A key that fails permanently.** A head is *permanently failing* when its error is not retryable (for example a
    record larger than the topic's limit, a missing topic, an authorization failure) or when `publish_attempts` passes
    a configured threshold. The key stays blocked. Its later events accumulate as pending and are never skipped or
    deleted. Every other key is unaffected. The relay keeps retrying the head at the maximum backoff, so the key
    resumes by itself once the cause is fixed (topic, ACL, size limit, relay code or configuration). This is a
    severity-1 incident for that entity, because its consumers see no new events: the metrics `blocked_keys` and the
    age of the oldest blocked head raise an alert when the age passes a configured limit, and the log names a hash of
    the key, never the payload. There is no ordinary way to drop or skip the head, because no ledger role may delete a
    pending row (D7.5). If the event can never be sent, the way out is a code or configuration fix, or a quarantine
    record (D7.12). Nothing deletes the event.
4. **Publish succeeds but marking fails** (a crash or database error between the ack and the UPDATE): the row stays
   pending and is sent again with the same `event_id`. This is the at-least-once case (D3.1). Consumers de-duplicate
   on `event_id` (D4). The relay counts such duplicates as a metric; they are not an error to repair.
5. **Unpublished rows are never deleted, by anyone in the policy.** No role holds DELETE on a pending row, a trigger
   refuses it, the cleanup job's predicate excludes it, and the break-glass role cannot delete either: it can only add
   a quarantine record (D7.12). Someone with superuser rights who disables triggers is outside the policy (ADR 0014
   D3.2).
6. **Cleanup path (`ledger_cleanup`), separate from publishing.** A scheduled job, never part of the relay's
   transaction, deletes in small batches (batch size and schedule are configuration) every outbox row with
   `published_at < now() − outbox_retention` and every inbox row with `received_at < now() − inbox_retention`. A
   failed delete is harmless: the row stays and the next run retries it. Alert when the oldest published row is older
   than twice `outbox_retention` (cleanup stuck).
7. **Retention: three buffers, three purposes, three owners (proposals).**

   | Buffer | Purpose | Owner and approver | Default |
   |---|---|---|---|
   | Kafka topic | Lets consumers catch up and replay recently delivered events by offset | Platform / Kafka operations (ADR 0014 D5, D5.1) | at least 7 days |
   | Ledger outbox, published rows | Delivery-side recovery buffer: re-send after the topic has aged out; diagnose delivery | Ledger team (cleanup job `ledger_cleanup`) | 14 days (inference: twice the topic minimum) |
   | Consumer inbox | Dedupe memory: recognise every duplicate that an earlier stage can still deliver | Each consumer team for its own inbox; the Ledger team for its own inbox of `AccountStateChanged` events (ADR 0022 D2) | 30 days (inference) |

   **Retention ordering (rule):** `Kafka retention ≤ Outbox retention ≤ Inbox retention`, that is `7 days ≤ 14 days ≤
   30 days` with the proposed defaults. The inbox must also exceed the outbox by at least the maximum consumer lag
   (inference): an event re-sent on the last day of outbox retention was first received later than it was published,
   and its inbox row must still be alive when the copy arrives. The 16-day margin of the defaults covers this. **Why
   the inbox is the longest:** duplicates come from every stage before it: a producer retry after a lost ack, a
   re-publish from the outbox (up to `outbox_retention`), an offset reset by an operator (up to the topic retention),
   a consumer that was down and reprocesses. The dedupe memory must outlive the longest-lived copy that can still be
   re-delivered, plus a margin for consumer downtime. Once an inbox row is pruned, a late duplicate repeats its side
   effect, which may move money, whereas an unneeded inbox row costs one small row. The cost is asymmetric, so the
   inbox errs long. The inbox is also the only defence that works whichever stage re-sent. The numbers are proposals;
   the owner may change them as long as the rule holds. These retentions are operational; archival is D7.8.

   **Approval.** The proposed values 7 / 14 / 30 days are for the owner to accept with this ADR. After acceptance:
   Kafka retention is changed only with the approval of Platform / Kafka operations and the written agreement of the
   Ledger owner, because the rule depends on it. Outbox retention is changed only with the approval of the Ledger
   owner, after consulting operations (storage) and compliance/reporting (D7.8). Each inbox retention is changed only
   with the approval of the team that runs that consumer (for the Ledger's own inbox, the Ledger owner), and never
   below the outbox retention plus that consumer's maximum lag. Every change also needs architecture approval, which
   confirms that the ordering rule `Kafka retention ≤ Outbox retention ≤ Inbox retention` still holds and refuses a
   change that breaks it. Architecture approval means the approval of the owner of this ADR set, or of the person the
   owner designates; no other body is assumed. A change is recorded in the configuration repository or the runbook; it
   needs a new ADR only if it changes the ordering rule itself.
8. **Archival and audit are separate from cleanup.** Operational outbox cleanup only bounds the delivery tables. It
   is not an archive and not the audit record. Regulatory archival requirements (what must be kept, for how long, in
   what form and where) are owned by compliance/reporting, with legal/DPO. They are decided separately (ADR 0025 D5.4,
   review §20.1) and are not met by keeping outbox rows. The audit history of the ledger is the append-only journal,
   the status, hold, FX, settlement and provisioning event tables and the idempotency records (ADR 0014 D3.2), kept
   until that decision is made. Because those tables hold what the events are built from, the outbox needs no archive
   step in v1 (inference; a test that rebuilds each event type from them must confirm it). If compliance requires
   archived copies of published events, the cleanup job must first copy the rows to append-only storage and verify the
   copy; that design is not made here.
9. **Recovery and replay.** (a) Crash, restart or leader change: pending rows are sent again (rule 4). (b) A consumer
   replays inside topic retention: reset its offsets; the inbox de-duplicates. (c) Re-send after the topic has aged
   out but inside `outbox_retention`: an operator runbook re-publishes a range of published or quarantined rows with
   the original `event_id`, payload and headers, under an `approval_ref`, and does not change `published_at`; the tool
   is not built (open item). A quarantined row that is re-sent this way stays QUARANTINED: neither `published_at` nor
   the quarantine record changes, because the record is append-only proof of the bypass. The late delivery is recorded
   by the runbook's own audit (`approval_ref`), not in the outbox (inference; the owner may add a delivery marker
   later). (d) Older than `outbox_retention`: the outbox cannot re-send. A consumer rebuilds its own derived state
   from the list APIs and the gapless `position_seq` (D4.1), or the Ledger team rebuilds an event stream for reporting
   from the retained tables. Such a rebuild is operational recovery or a reporting rebuild. It is not a replacement
   for the immutable audit history: it yields current or derived views, not a record of what was delivered, when, how
   often or in which schema version; it cannot recreate an event that was never emitted; and a rebuilt event is no
   evidence of past delivery.
10. **Inbox interaction.** The inbox key is `(consumer_name, message_id)` with `message_id = event_id`, inserted in
    the same transaction as the effect; the offset is committed after that commit (D4.1). Inbox rows are kept for
    `inbox_retention ≥ outbox_retention + the maximum consumer lag` (D7.7), so every duplicate that the outbox or the
    topic can still deliver is recognised. `ledger_cleanup` prunes them; before this rule no role could delete inbox
    rows, so the retention promised in D5.1 could not have been carried out.
11. **Metrics:** pending depth and age (existing alert), `publish_attempts` of the oldest pending row, duplicate
    publishes, age of the oldest published row, `blocked_keys`, the age of the oldest blocked head and the number of
    quarantined events (D7.12).
12. **Quarantine: the only bypass of a blocked event.** Decided 2026-11-15; it replaces both the break-glass removal
    and the open *dead state* question.
   - *State.* There is no stored status and no dead state. An outbox row is PENDING (`published_at IS NULL`, no
     quarantine record), PUBLISHED (`published_at` set) or QUARANTINED (`published_at IS NULL` and a record in
     `outbox_quarantine`). The outbox row itself is never changed by a quarantine.
   - *Record* (`outbox_quarantine`: append-only, one per event): `outbox_id`; `tenant_id`, `message_key`,
     `publish_attempts` and `last_error`, copied from the outbox row by a trigger; `blocked_at` (when the key started
     to block: the head's `first_failed_at`, else its `created_at`); `reason_code` (`UNSENDABLE_PAYLOAD`,
     `RECORD_TOO_LARGE`, `TOPIC_CONFIGURATION`, `AUTHORIZATION`, `OTHER`) and `reason_text` (optional, see the
     metadata rules); `approval_ref` (from the approval workflow; not empty); `operator_id` (the human; not empty);
     `db_role` (the session role); `quarantined_at`.
   - *Metadata rules.* `reason_code` is one of `UNSENDABLE_PAYLOAD`, `RECORD_TOO_LARGE`, `TOPIC_CONFIGURATION`,
     `AUTHORIZATION`, `OTHER` (database CHECK); a new value is an ADR change. `reason_text` is optional (it may be
     NULL) and is an operational explanation only: printable ASCII, 1 to 300 characters (database CHECK on the form).
     It must not contain event payloads; amounts, balances or other financial data; customer identifiers (`owner_ref`,
     `external_account_id`, names, any value tied to a person); secrets (tokens, keys, passwords, connection strings);
     or raw exception, stack-trace or SQL text. The event is referred to by `outbox_id`, never by its content. The
     database can check only the form; the content rule is a procedure that the approver and the operator both apply,
     and it is part of the grant review for `ledger_repair`. `last_error`, on the outbox row and in its copy, is an
     error class name only, for example `RecordTooLargeException`, matching `[A-Za-z0-9_.:-]{1,200}` (database CHECK),
     never an exception message; the relay maps broker errors to class names. `approval_ref` and `operator_id` are
     identifiers: printable ASCII without a space, up to 128 characters (database CHECK). Alerts and logs about a
     quarantine carry `outbox_id`, `reason_code` and `approval_ref`, never `reason_text`. The record is readable
     through `ledger_readonly` by operations and compliance only.
   - *Who and when.* Only the break-glass role `ledger_repair` (granted per incident, ADR 0014 D3.1; inference: the
     existing break-glass role is reused rather than a new one) may insert a record, and only for an unpublished row;
     a trigger refuses a published row. Nobody can update, delete or truncate a record, the owner included (trigger).
   - *Effect.* The outbox row stays with `published_at` NULL and nothing deletes it (D7.5). The relay skips it, and it
     no longer blocks its key: later events of the key are sent. The relay never sends it. Consumers see a gap in
     `aggregate_sequence` (D2) and resync. Once the cause is fixed the event may still be sent late by the re-publish
     runbook (D7.9c); the sequence lets consumers apply it.
   - *Proof.* The record, the retained outbox row (payload, `event_id`, `aggregate_sequence`) and the audit of the
     role grant show that an event was bypassed deliberately, by whom, with which approval and why. Operations and
     compliance read the list through `ledger_readonly`. Every insert raises an alert.
   - *A DEAD state is not needed.* A stored DEAD flag would need an UPDATE of an unpublished row, would reduce the
     approval metadata to a bare status, and would give nothing that the record does not give. Reconsider only if a
     measured operational need appears.

Database: roles, grants and triggers are in ADR 0014 D3 and review §7.3 (tested on PostgreSQL 18.7 on 2026-11-15,
single-session checks only).

## Consequences
- One relay bounds the publish rate; outbox depth and age are alerts.
- If the database restores to a point before a sent event, the event has no backing transaction; with RPO = 0
  inside the HA domain (ADR 0014 D5) this only arises in a cross-region restore.
- ADR 0008's outbox step returns; the relay (publish path) and the cleanup job (delete path) each have their own
  database role.
- A permanently failing event blocks only its own ordering key; other keys and tenants keep flowing (D3.2, D7.3a).
- Database changes (review §7.3, tested on PostgreSQL 18.7 on 2026-11-15): `outbox_events` gains `aggregate_type`,
  `aggregate_id`, `aggregate_sequence` (unique per aggregate, ordering key checked) and `first_failed_at`; the new
  append-only table `outbox_quarantine`; `recon_runs.event_seq`; the relay may also update `first_failed_at` and read
  the quarantine table. TRUNCATE of `outbox_events` is now refused by PostgreSQL itself, because the quarantine table
  references it; the trigger stays as a second layer. `last_error`, `reason_text`, `approval_ref` and `operator_id`
  are constrained to controlled or identifier-shaped values.

## Needs benchmark or human verification
- Relay throughput and publish lag (ADR 0017, capacity section).
- End-to-end test of the BOM's kafka-clients and Spring Kafka pair.
- Performance follow-up: benchmark the head-per-key selection of D3.2 at a realistic pending depth, with many keys and
  one long-blocked key. Add a partial index on `(message_key, outbox_id) WHERE published_at IS NULL` only if the
  measurements justify it. The DDL is not changed here.

## Dependencies and acceptance order

**Depends on** (all cited in the text above; all are Proposed): 0014 (roles, grants, triggers, durability: D3, D5),
0015 (decimal strings), 0016 (reversal rules, `ux_ltx_single_reversal`: D1.1, D3), 0017 (`position_seq`, lock order,
write path: D2, D4, D5), 0019 (correlation id, contract freeze: D2.3, D3), 0021 (status history tables, wallet and
bucket locks: D3, D4), 0022 (account events and seal: D2, D6), 0023 (hold lock and history: D3), 0025 (settlement,
reconciliation, retention: D3, D5.4), 0026 (clock port and modules: D1, D2). **Not dependencies:** 0018 and 0020.
**Amends** 0003, 0008 and 0013, which stay Accepted with their header notes (ADR README).

**Circular citations.** 0014, 0017, 0019, 0021, 0022, 0023 and 0026 cite this ADR in turn, so the set cannot be
accepted strictly one at a time without forward references.

**Recommended order.** (1) 0014, 0015, 0016, 0017: roles and DDL, money, journal, projection; the sequences and the
roles stand on them. (2) 0021, 0022, 0023, 0025 (the history tables and locks that define each aggregate's sequence),
together with 0019 and 0026 (contract and module rules). (3) 0024. Accepting steps 1 to 3 in one decision is equally
valid. What must not happen is accepting 0024 while 0014 (roles, grants, triggers) or 0017 (`position_seq`, locks) is
still Proposed.

## Open items

**For the owner to accept with this ADR** (decisions inside it, not implementation):
- The D5 proposals (replication factor 3, `min.insync.replicas=2`, no schema registry, no activity topic).
- The retention proposal 7 / 14 / 30 days and its approvers (D7.7).
- The quarantine reason codes and the reuse of `ledger_repair` for quarantine records (D7.12).

**Outside this ADR:**
- Archival before deletion (owner: compliance/reporting, with legal/DPO; assumption: not required for operational
  cleanup, D7.8).

**Remaining implementation decisions** (none reopens a decision above):
- *Payload schemas:* the business payload message of each event type. The envelope is decided (D2).
- *Kafka configuration:* partition count per topic; producer settings beyond `acks=all` and idempotence; topic ACLs
  (including the producer rule in the envelope table); dead-letter topics; the tested pairing of kafka-clients and
  Spring Kafka.
- *Relay implementation:* backoff values, the permanent-failure threshold and alert limits (D7.3a); cleanup schedule
  and batch size; the head-per-key benchmark and, only if measured, the partial index; the re-publish tool (D7.9c);
  the reconstruction test (D7.8).
- *Consumer contracts:* `event_sequence` and `reversed_by_transaction_id` on the read messages (D2.6, D2.7); the
  bounded wait before a resync (D2.4); inbox and dead-letter handling per consumer; where the Account Service event
  contract lives (ADR 0022).

## Alternatives rejected
- **No events and no outbox in v1 (old 0021).** Consumers would poll the per-account posting list; one later ADR
  would add the event contract, outbox, relay and its user together; delivery by status, not cursor. Lost because
  consumers (Payments, Operations) exist in the owner baseline (review §19.1, REJECT). Its fourth decision
  (claim by status, per-position order only, de-duplicate on event id) is kept in D2 and D3.
- **Several relays with `SKIP LOCKED`.** Breaks per-key order.
- **Cursor over `outbox_id`.** Skips late commits (ADR 0017 D2).
- **Keep the outbox with no reader.** Rows accumulate and the format freezes before a consumer exists (moot now).
- **Change data capture.** Not evaluated.
- **Delete a row in the publish transaction, on ack.** Loses the replay and audit window, and couples a delete
  failure to the ack.
- **Keep outbox rows for ever.** The table and its indexes grow without bound.
- **Let the relay hold DELETE.** The publish path could then drop an unpublished event.
- **Archive before delete as a hard rule.** Cost without a requirement (D7.8).
- **Skip or park a failing row.** Reorders events of the same key (D3).
- **Global stall on the first failing row.** One bad entity would halt every event of every tenant; replaced by
  per-key blocking (D3.2, D7.3).
- **Skip the failing row and send the next event of the same key.** Reorders the key.
- **A global `outbox_id` order as the contract.** It promises more than the transactions can deliver (ADR 0017 D2) and
  couples unrelated keys; order is promised per key only.
- **Physical deletion of an unsendable event by break-glass.** Destroys the evidence of what was not delivered;
  replaced by the quarantine record (D7.12).
- **A stored DEAD or SKIPPED status on the outbox row.** Needs UPDATE of unpublished rows and carries no approval
  metadata (D7.12).
- **Counters on every aggregate row for the sequence.** The history tables already carry `event_seq` (D2.2).
- **Wallet or tenant as the key of TransactionPosted.** A transaction spans several positions and wallets, and
  postings do not take the wallet lock, so the events of one key would not be serialised (D1).
- **Start the settlement sequence at 1 without an Opened event (an offset from `event_seq`).** Breaks the
  one-row-one-event rule that keeps the sequence equal to the history row; replaced by `SettlementOpened`.
- **A stored counter or a `transaction_events` table for the Transaction sequence.** The transaction is immutable and
  has no concurrent writers (D2.2).
- **Free-text `last_error` and an unbounded `reason_text`.** They would let a payload, an amount or a customer
  identifier leave through operational metadata (D7.12).
