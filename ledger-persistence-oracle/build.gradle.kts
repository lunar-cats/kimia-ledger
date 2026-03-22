description = "Oracle persistence adapter (plain JDBC, Flyway). All SQL lives here."

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":ledger-application"))
    implementation(libs.spring.jdbc)
    // ojdbc17, flyway-database-oracle and Testcontainers arrive with the first migration (Phase 2).
}
