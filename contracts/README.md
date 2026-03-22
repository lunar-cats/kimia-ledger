# Contracts

`proto/ledger/v1/*.proto` is the source of truth for the Kimia Ledger API (ADR 0004). REST is derived from the
`google.api.http` annotations (gRPC-JSON transcoding). `openapi/openapi.yaml` is **generated** from the protos
and committed; never edit it by hand.

```
buf.yaml            module config: deps (googleapis, pinned commit), lint (STANDARD + COMMENTS), breaking (FILE)
buf.lock            resolved dependency digests
buf.gen.yaml        OpenAPI generation (remote plugin community/google-gnostic-openapi, pinned)
proto/ledger/v1/    money, error, account, entry, ledger_service
openapi/            generated OpenAPI 3.0 document
```

## Commands (run in this directory)

```bash
buf lint                        # style + comment rules
buf format -w                   # format in place (CI uses -d --exit-code)
buf generate                    # regenerate openapi/openapi.yaml
buf breaking --against '../.git#branch=main,subdir=contracts'   # no breaking change inside ledger.v1
```

## Rules

- Changes inside `ledger.v1` are additive only: new fields, new enum values, new RPCs. Never renumber, remove,
  or change the type of a field; never change an HTTP path. A breaking change is a new package `ledger.v2`.
- Every message, field, enum value and RPC has a comment (lint enforces it); comments cite the invariant (I-n) or ADR they implement.
- Amounts are `int64` minor units with an ISO-4217 code (`ledger.v1.Money`). Never floating point.
- There is no tenant field; the tenant comes from the token (I-19).
- Each release publishes this directory's protos (with their googleapis imports) and the OpenAPI file as a versioned artifact (ADR 0012).
