# ADR 0015: REST is a hand-written adapter that conforms to the gRPC-JSON transcoding mapping

Status: Proposed · Date: 2026-11-14 · Amends 0004

## Context
Review finding B-2. ADR 0004 says REST *is* gRPC-JSON transcoding and lists a "transcoding runtime" as a
dependency. `CLAUDE.md` describes `ledger-api-rest` as a hand-written adapter. No transcoding runtime was
ever chosen.

Checked on 2026-11-14:
- Spring gRPC has no transcoding. The feature request (spring-projects/spring-grpc, issue 339) is open, with
  no milestone and no maintainer commitment (per the review; not re-checked here).
- `error.proto` documents HTTP 422 for its `FAILED_PRECONDITION` codes. The standard mapping in
  `google/rpc/code.proto` gives 400 for `FAILED_PRECONDITION` and 409 for `ABORTED` and `ALREADY_EXISTS`
  (verified, googleapis `google/rpc/code.proto`, master, read 2026-11-14).
- ADR 0004 gives the proto path as `contracts/ledger/v1/`. The files are in `contracts/proto/ledger/v1/` (S-4).

## Decision
1. The protos with their `google.api.http` options stay the **only definition** of the REST surface. The
   OpenAPI document stays generated.
2. `ledger-api-rest` is a **hand-written Spring MVC adapter**. No transcoding runtime, proxy or library is in
   the request path.
3. The adapter **conforms** to the transcoding mapping. For every `google.api.http` binding it serves the same
   method, path, path variables, query parameters and body.
4. Request and response bodies are the generated proto messages, parsed and printed with protobuf's own JSON
   mapping. No hand-written DTOs. No second JSON mapping for money.
5. A request with a field the server does not know is rejected with `INVALID_ARGUMENT`, on REST and on gRPC.
   A ledger must not drop a field the caller meant. (Clients, by contrast, must tolerate unknown fields and
   enum values in responses: ADR 0032.)
6. Errors are the `google.rpc.Status` JSON shape with `ledger.v1.LedgerErrorDetail` in `details` (ADR 0018).
   The correlation id is a header, not a field (ADR 0018).
7. The HTTP status is the standard `google.rpc.Code` mapping. **422 leaves the contract**; the codes that
   documented it return 400. The full table is in ADR 0018.
8. New paths use sub-resources, not the `:verb` suffix: `POST /v1/accounts/{account_id}/close`,
   `POST /v1/entries/{entry_id}/reversal`.
9. A conformance test reads the proto descriptors and fails if a binding is not served. A second test sends
   the same request over REST and gRPC and compares the response messages.

## Consequences
- Both adapters need the same mapping between proto messages and use cases. It exists once, in
  `ledger-contracts` (ADR 0031).
- A REST client that looks only at the status cannot tell `INVALID_ARGUMENT` from `FAILED_PRECONDITION`.
  The contract already says: branch on the error code.
- If Spring gRPC ships transcoding later, the adapter can be replaced without a contract change.
- The protobuf JSON parser also accepts a JSON number for an `int64` field. The OpenAPI document declares
  amounts as strings; the integration guide must tell clients to send strings.
- `ledger-api-rest` gains a protobuf dependency (`protobuf-java-util`). Licence and version are checked before
  it is added.
- Unverified, to settle in a spike: Spring MVC message conversion for protobuf JSON on Spring Framework 7, and
  how the router treats `:` in a path. Decision 8 avoids the second question.

## Alternatives rejected
- **Transcoding proxy in front of the gRPC port.** A mandatory extra component. It would terminate TLS in front
  of the service, against ADR 0023. Errors produced by the proxy carry no ledger error detail.
- **Community transcoding library in the process.** A dependency with one maintainer in the money path.
- **Keep 422.** It separates rule violations from malformed requests at HTTP level. REST would then differ from
  the standard mapping, and "REST" would again have two definitions.
- **REST-only DTOs with a JSON library.** A second schema; it drifts.

## Not decided here
Whether v1 needs REST at all. The first consumer is .NET, which has a gRPC client. ADR 0004 says both
transports are first-class; this ADR keeps that. Shipping gRPC only in v1 would remove one adapter. That is
a maintainer decision.

## Relation to the review
Agrees with B-2. Adds decision 5 (unknown fields), decision 8 (path style) and the descriptor-based test.
