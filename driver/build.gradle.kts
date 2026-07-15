import java.security.MessageDigest

description = "Valet JDBC driver (slim) — jdbc:boundary:// scheme. Requires PGJDBC on the runtime classpath."

dependencies {
    api(project(":core"))

    // PGJDBC is loaded reflectively at runtime (§9); declared so the slim POM lists it
    // and so tests can drive a real PostgreSQL connection.
    implementation(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ---- integration tests (M3): boundary dev + Testcontainers PostgreSQL ----
// Kept in their own source set so the default `build` never needs Docker or the boundary CLI.
// Run explicitly: `./gradlew integrationTest` (CI does this on Linux only).
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

configurations.getByName("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

dependencies {
    "integrationTestImplementation"(platform(libs.junit.bom))
    "integrationTestImplementation"(libs.junit.jupiter)
    "integrationTestImplementation"(libs.jackson.databind)
    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"(libs.testcontainers.junit)
    "integrationTestImplementation"(libs.testcontainers.postgresql)
    "integrationTestImplementation"(libs.postgresql)
    "integrationTestImplementation"(libs.hikaricp)
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
    "integrationTestRuntimeOnly"(libs.slf4j.simple)
}

// The integration tests download their own `boundary` CLI — no manual install, correct
// OS/arch, checksum-verified, cached under <root>/.boundary so it survives `clean`.
val boundaryVersion = providers.gradleProperty("valet.boundaryVersion").getOrElse("0.21.3")
val boundaryDir = File(rootDir, ".boundary/$boundaryVersion")
val boundaryBinaryName = if (System.getProperty("os.name").lowercase().contains("win")) "boundary.exe" else "boundary"
val boundaryBinary = File(boundaryDir, boundaryBinaryName)

val downloadBoundary = tasks.register("downloadBoundary") {
    description = "Downloads and verifies the boundary CLI used by the integration tests."
    group = "verification"
    outputs.file(boundaryBinary)
    doLast {
        if (boundaryBinary.exists()) return@doLast
        val osName = System.getProperty("os.name").lowercase()
        val platform = when {
            osName.contains("mac") || osName.contains("darwin") -> "darwin"
            osName.contains("win") -> "windows"
            else -> "linux"
        }
        val arch = if (System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") })
            "arm64" else "amd64"
        val zipName = "boundary_${boundaryVersion}_${platform}_${arch}.zip"
        val base = "https://releases.hashicorp.com/boundary/$boundaryVersion"

        boundaryDir.mkdirs()
        val zip = File(boundaryDir, zipName)
        uri("$base/$zipName").toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }

        val sums = uri("$base/boundary_${boundaryVersion}_SHA256SUMS").toURL().readText()
        val expected = sums.lineSequence().first { it.trim().endsWith(zipName) }.substringBefore(' ').trim()
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(zip.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        check(expected == actual) { "boundary $zipName checksum mismatch (expected $expected, got $actual)" }

        copy { from(zipTree(zip)); into(boundaryDir) }
        boundaryBinary.setExecutable(true)
        zip.delete()
        logger.lifecycle("boundary $boundaryVersion ready at $boundaryBinary")
    }
}

val bundleJar = File(project(":bundle").layout.buildDirectory.get().asFile, "libs/valet-bundle-${project.version}.jar")

tasks.register<Test>("integrationTest") {
    description = "Runs Boundary + Testcontainers integration tests (downloads boundary; needs Docker)."
    group = "verification"
    // The isolated-classloader test loads the shaded bundle jar the way a JetBrains IDE / DBeaver would.
    dependsOn(downloadBoundary, ":bundle:shadowJar")
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("valet.it.boundary", boundaryBinary.absolutePath)
    systemProperty("valet.it.bundle", bundleJar.absolutePath)
    shouldRunAfter(tasks.named("test"))
}
