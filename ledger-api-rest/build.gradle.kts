description = "REST inbound adapter"

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":ledger-application"))
    implementation(libs.spring.webmvc)
}
