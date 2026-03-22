description = "Spring Boot wiring only"

plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":ledger-core"))
    implementation(project(":ledger-application"))
    implementation(project(":ledger-persistence-oracle"))
    implementation(project(":ledger-api-rest"))
    implementation(project(":ledger-api-grpc"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit)
}
