# Integrating with Kimia Ledger

Kimia Ledger ships **no client SDKs** (ADR 0012). Each release publishes a contracts artifact; you generate a
client from it in your own repository.

**Status: Proposed.** The target API is set by ADR 0015 (money), ADR 0019 (API contract and errors) and
ADR 0020 (identity and scopes). Those ADRs are Proposed until the owner accepts them. The `.proto` files and
`openapi.yaml` in `contracts/` still show the old draft. They are being rewritten to match the ADRs. Until that
rewrite is released as `v1.0.0`, `ledger.v1` is a draft and may change in any way (ADR 0019 D3).

## What a release publishes

`kimia-ledger-contracts-<version>.zip` attached to the GitHub release, containing:

```
proto/ledger/v1/*.proto                       # the API (source of truth; draft, being rewritten)
proto/ledger/events/v1/*.proto                # event schemas (ADR 0024); packaging to be defined
proto/google/api/{annotations,http,field_behavior}.proto   # googleapis files the protos import
openapi/openapi.yaml                           # OpenAPI 3.0 generated from the protos
CHECKSUMS.sha256
```

Pin the version you consume. Until `v1.0.0` the contract is a draft. From `v1.0.0`, changes inside `ledger.v1`
are additive only, and `buf breaking` blocks any other change (ADR 0019 D3). A newer contract of the same major
version is then safe to adopt.

## Accounts and wallets

- Accounts are not in this API. They live in the external Account Service (ADR 0021 D1). The ledger has no
  `CreateAccount`, `GetAccount`, `ListAccounts` or `CloseAccount` call.
- The ledger owns wallets and balance buckets. `CreateWallet` starts a wallet in `PROVISIONING`. It becomes
  `ACTIVE` after the ledger has the account references it needs (ADR 0021 D3).
- Clients never send an account id to create or change an account (ADR 0021 D1.4). Postings target a bucket,
  or a wallet's primary bucket for an asset (ADR 0021 D4.5). A posting target is one of `bucket_id`, `wallet_primary { wallet_id, asset_code }`, or `external_account_id` (system accounts only, ADR 0016 D1.2). A system account id must resolve to a SYSTEM account at the Account Service, otherwise `ACCOUNT_UNKNOWN` (ADR 0021 D1.4). Customer accounts are never targeted by account id.

## Cross-cutting conventions (all transports)

- **Auth** (ADR 0020 D1, D2): OAuth2 client-credentials. Send the JWT as `Authorization: Bearer <token>` (REST) or
  `authorization` metadata (gRPC). mTLS is required and ends in the service, not in a proxy. Your tenant is a claim
  in the token (the claim name is set per issuer; default `tenant_id`). The API has no tenant field. By default the
  token must be bound to your client certificate (ADR 0020 D1.4).
- **Scopes** (ADR 0020 D5): `wallets:read`, `wallets:write`, `wallets:freeze`, `transactions:read`,
  `transactions:write`, `holds:read`, `holds:write`, `balances:read`, `postings:read`, `fx:write`,
  `settlement:write`, `reconciliation:*`. `wallets:freeze` also needs an `approval_ref` (ADR 0021 D5).
  `transactions:system` and `transactions:exceptional` are granted only to named services; `exceptional` also
  needs an `approval_ref` (ADR 0021 D5). `accounts:fence` belongs to the Account Service only. The former account
  scopes are removed (ADR 0021 D1).
- **Idempotency** (ADR 0018): every write carries `idempotency_key`, 1 to 255 printable ASCII characters with no
  spaces (a UUID works). The key is unique per tenant across all write operations. The same key, client, operation
  and payload returns the original result, marked as a replay. The same key with a different payload, operation or
  client returns `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`; use a new key. A rejection that depends on ledger state (for
  example `INSUFFICIENT_FUNDS`) is recorded: the same key returns the same rejection. After you fix the cause, send
  a new key. Keys do not expire in v1. Keep your `client_id` when you rotate credentials, or in-flight retries
  become conflicts (ADR 0018 Consequences).
- **Correlation** (ADR 0019 D2): send an `x-correlation-id` header (HTTP and gRPC metadata), 1 to 128 characters
  from `A-Z`, `a-z`, `0-9`, `.`, `_`, `:` and `-`. If it is absent or invalid, the service makes one; an invalid
  value is dropped. The value is echoed in the response header and the error detail. The service also returns an
  `x-request-id` for every request. Events carry the correlation id too (ADR 0024 D1).
- **Money** (ADR 0015): a `Money` value is an asset code plus an `amount` string. Send `amount` as a JSON string of
  integer minor units, for example `"1250000"`. Positive amounts match `[1-9][0-9]{0,37}`; balances may start
  with `-`. No decimal point, exponent or leading zero. Each asset code has one fixed scale (0 to 18) that tells you
  how to display the amount. Do not send floats or JSON numbers.
- **Pagination**: `page_size` (server-capped) and opaque `page_token`.
- **Errors** (ADR 0019 D2): every error is a `google.rpc.Status` whose `details` holds one
  `ledger.v1.LedgerErrorDetail`. Its `code` is a stable `ErrorCode` value, for example `UNBALANCED_ENTRY`,
  `INSUFFICIENT_FUNDS`, `ACCOUNT_UNKNOWN` or `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`. gRPC and REST use the same `Status` shape. Branch on
  `code`, not on `message`. The table in ADR 0019 D2 gives, for each code, the gRPC status, the HTTP status, whether
  the outcome is recorded, and the action to take.
  - `CONTENTION` (gRPC `ABORTED`), `UNAVAILABLE` and `INTERNAL`: retry with the same key after a backoff.
  - `RATE_LIMITED`: retry with the same key after a delay.
  - Some responses have no detail (a proxy, the framework or the gRPC library produced them). Use the HTTP or gRPC
    status then.
- **Tolerance** (ADR 0019 D3.5): accept unknown fields, unknown enum values (including new `ErrorCode` values) and
  errors without a detail. The service rejects unknown fields in your request (`INVALID_ARGUMENT`), so send only
  fields from the current contract. The .NET and Java parser settings for this are to be defined.
- **No personal data.** `external_ref` and `owner_ref` are opaque to the ledger and are kept with the
  journal. Put your own
  ids there, never names, emails or anything that identifies a person (ADR 0011).

## .NET (gRPC with Grpc.Tools)

The message, field and service names below come from the current draft protos. They change with the rewrite; regenerate
after it.

1. Unzip the contracts into `Protos/` in your project.
2. Reference the packages and include the protos:

```xml
<ItemGroup>
  <PackageReference Include="Grpc.Net.Client" Version="x.y.z" />
  <PackageReference Include="Google.Protobuf" Version="x.y.z" />
  <PackageReference Include="Grpc.Tools" Version="x.y.z" PrivateAssets="All" />
</ItemGroup>
<ItemGroup>
  <Protobuf Include="Protos/ledger/v1/*.proto" ProtoRoot="Protos" GrpcServices="Client" />
  <Protobuf Include="Protos/google/api/*.proto" ProtoRoot="Protos" GrpcServices="None" />
</ItemGroup>
```

3. Create a channel with mTLS and a bearer token:

```csharp
var handler = new SocketsHttpHandler();
handler.SslOptions.ClientCertificates = new X509CertificateCollection { clientCert };
var channel = GrpcChannel.ForAddress("https://ledger.example.internal", new GrpcChannelOptions { HttpHandler = handler });
var callCredentials = CallCredentials.FromInterceptor((_, metadata) => { metadata.Add("authorization", $"Bearer {token}"); return Task.CompletedTask; });
var client = new LedgerService.LedgerServiceClient(channel.Intercept(/* correlation id interceptor */));
```

Use the generated request types; set `IdempotencyKey = Guid.NewGuid().ToString()` once per logical operation and reuse it on retries. Pin exact package versions in your repository.

## .NET (REST from OpenAPI)

Use any OpenAPI generator (Kiota, NSwag, openapi-generator) against `openapi/openapi.yaml`. Until the rewrite is
released, that file still shows the old draft.
The REST paths and JSON field names are derived from the proto (`google.api.http`). A hand-written adapter serves
them with the same mapping as gRPC (ADR 0019 D1). JSON uses lowerCamelCase field names and enums as strings. Money
amounts are decimal strings, for example `"amount": "1250000"`. No money field is an `int64` (ADR 0015).

Errors on REST use the `google.rpc.Status` JSON shape. The `details` array holds one object with
`"@type": "type.googleapis.com/ledger.v1.LedgerErrorDetail"` and the fields `code`, `message`, `correlationId`,
`requestId` and `fieldViolations` (ADR 0019 D2). The OpenAPI document shows `details` as a generic `Any`. The exact
shape is in `proto/ledger/v1/error.proto`, which is being rewritten.

## Other languages

- gRPC: `protoc` or `buf generate` with the proto directory as the include root.
- REST: any OpenAPI 3 generator, or a plain HTTP client following the OpenAPI document.

## Verifying a download

```
sha256sum -c CHECKSUMS.sha256
```

Release artifacts are also signed with cosign; verification instructions ship with the first release.

## Regenerating locally

```
cd contracts && buf dep update && buf lint && buf generate   # writes openapi/openapi.yaml
```
