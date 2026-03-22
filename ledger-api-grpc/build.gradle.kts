description = "gRPC inbound adapter"

dependencies {
    implementation(project(":ledger-application"))
    // grpc-stub / protobuf-java and stubs generated from contracts/proto arrive with the first adapter (Phase 2).
}
