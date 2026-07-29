plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.detekt.api)

    // detekt-test 2.0.0-alpha.5 depends on a detekt-api test-fixtures variant Maven Central never
    // published, so testRuntimeClasspath fails to resolve. `lint()` never reaches those fixtures,
    // so dropping the edge and supplying detekt-api directly is enough.
    testImplementation(libs.detekt.test) {
        exclude(group = "dev.detekt", module = "detekt-api")
    }
    testImplementation(libs.detekt.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.bundles.testing.runtime)
}

tasks.withType<Test>().configureEach {
    // One test reads the real `detekt.yml`, which lives outside this module: hand it over as an
    // absolute path, and declare it as an input so editing it re-runs the tests instead of
    // reporting a stale pass.
    val detektConfiguration = rootProject.file("config/detekt/detekt.yml")
    inputs
        .file(detektConfiguration)
        .withPropertyName("detektConfiguration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("pinryReborn.detektConfigurationPath", detektConfiguration.absolutePath)
}
