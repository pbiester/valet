description = "Valet core — Boundary session management and CLI wrapper. No JDBC driver dependency."

dependencies {
    // Jackson is exposed via api so the slim/bundle artifacts pick it up transitively.
    api(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
