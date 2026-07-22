plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
}

dependencies {
    implementation(project(":api-domain"))
    implementation(project(":api-utilities"))

    implementation(libs.commons.text)
    implementation(libs.kotlin.logging)
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.jakarta.transaction.api)

    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)

    // Test-only: pins the export archive's published JSON shape (golden-JSON test in
    // ExportContentGoldenJsonTest) with a mapper configured exactly like the real adapter's
    // (FilesystemZipExportArchiveStore). Main source stays Jackson-free by design (Jackson is
    // adapter-only, per docs/plans/2026-07-22-user-data-export.md's tech stack) -- Konsist scans
    // production sources only, so this test-scoped dependency does not weaken that guardrail.
    testImplementation(platform(libs.quarkus.bom))
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.datatype.jsr310)
}
