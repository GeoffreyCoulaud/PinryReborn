plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
}
dependencies {
    implementation(project(":api-domain"))
    implementation(libs.jbcrypt)
    compileOnly(libs.jakarta.cdi.api)
    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
