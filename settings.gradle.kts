rootProject.name = "kimia-ledger"

include(
    "ledger-core",
    "ledger-application",
    "ledger-persistence-oracle",
    "ledger-api-rest",
    "ledger-api-grpc",
    "ledger-server",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
