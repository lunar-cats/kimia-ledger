# Integrating with Kimia Ledger

Kimia Ledger ships **no client SDKs** (ADR 0012). Each release publishes a contracts artifact; you generate a
client from it in your own repository.

## What a release publishes

`kimia-ledger-contracts-<version>.zip` attached to the GitHub release, containing:

```
proto/ledger/v1/*.proto          # the API (source of truth)
proto/google/api/annotations.proto, http.proto   # transcoding annotations the protos import
openapi/ledger.v1.openapi.yaml   # generated from the protos
CHECKSUMS.sha256
```

Pin the version you consume. Within `ledger.v1` changes are additive only (`buf breaking` enforced), so a newer
contract of the same major version is safe to adopt.

## Cross-cutting conventions (all transports)

- **Auth**: OAuth2 client-credentials. Send the JWT as `Authorization: Bearer <token>` (REST) or `authorization`
  metadata (gRPC). mTLS is required between services. Your tenant is a claim in the token; the API has no tenant field.
- **Scopes**: `accounts:read|write`, `entries:read|write`, `balances:read`, `postings:read`.
- **Idempotency**: every write carries `idempotency_key` (your UUID). Retry with the same key and same payload
  until you get a response; you will get the original result, never a duplicate.
- **Correlation**: pass `correlation_id` on every request; it is echoed in responses, logs and events.
- **Money**: amounts are integer minor units (`int64`, or decimal string) plus an ISO-4217 code. `1050 EUR` means €10.50. Never send floats.
- **Pagination**: `page_size` (server-capped) and opaque `page_token`.
- **Errors**: a stable `ErrorCode` enum (for example `UNBALANCED_ENTRY`, `INSUFFICIENT_FUNDS`,
  `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT`, `ACCOUNT_NOT_FOUND`). gRPC: in status details. REST: in the JSON error body. Branch on the code, not on the message.
- **No personal data.** `external_ref` and `metadata` are opaque to the ledger and stored forever. Put your own
  ids there, never names, emails or anything that identifies a person (ADR 0011).

## .NET (gRPC with Grpc.Tools)

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

Use any OpenAPI generator (Kiota, NSwag, openapi-generator) against `openapi/ledger.v1.openapi.yaml`.
The REST paths and JSON field names are derived from the proto (`google.api.http`), so they stay in lockstep with gRPC. JSON uses lowerCamelCase field names and `int64` values as strings.

## Other languages

- gRPC: `protoc` or `buf generate` with the proto directory as the include root.
- REST: any OpenAPI 3 generator, or a plain HTTP client following the OpenAPI document.

## Verifying a download

```
sha256sum -c CHECKSUMS.sha256
```

Release artifacts are also signed with cosign; verification instructions ship with the first release.
