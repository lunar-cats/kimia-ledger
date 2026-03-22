description = "gRPC inbound adapter"

dependencies {
    implementation(project(":ledger-application"))
    // grpc-stub / protobuf-java and the generated stubs arrive with the contracts (Step 5).
}
