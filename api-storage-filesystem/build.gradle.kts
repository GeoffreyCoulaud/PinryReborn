plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
}
dependencies {
    implementation(project(":api-domain"))
    compileOnly(libs.jakarta.cdi.api)

    implementation(platform(libs.quarkus.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
