import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

description = "Valet bundle — fat JAR with PGJDBC and (shaded) Jackson. Drop-in for GUI clients."

dependencies {
    implementation(project(":driver"))
}

tasks.named<Jar>("jar") {
    // The plain jar is not the deliverable; the shadowJar is.
    archiveClassifier.set("thin")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("valet-bundle")
    archiveClassifier.set("")

    // Relocate Jackson so it cannot clash with a host application's copy (§11).
    relocate("com.fasterxml.jackson", "dev.isonet.valet.shaded.jackson")

    // Do NOT relocate org.postgresql — it breaks unwrap(PGConnection.class) (§11).

    // Merge META-INF/services so both ValetDriver and org.postgresql.Driver auto-register.
    // The bundle module ships its own authoritative java.sql.Driver listing both, because a
    // project dependency's service file (written directly from its resource output) can
    // otherwise clobber PGJDBC's rather than merge with it (§11).
    mergeServiceFiles()
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}
