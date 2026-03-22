# Contracts

`ledger/v1/*.proto` is the source of truth for the Kimia Ledger API. REST is derived via gRPC-JSON transcoding
(`google.api.http`) and the OpenAPI document is generated from the proto, never hand-edited.
Filled in Step 5 of the kickoff; guarded by `buf lint` and `buf breaking`.
