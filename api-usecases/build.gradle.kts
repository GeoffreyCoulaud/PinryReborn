plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jandex)
}

dependencies {
    implementation(project(":api-domain"))
    implementation(project(":api-utilities"))

    implementation(libs.jbcrypt)
    implementation(libs.commons.text)
    implementation(libs.kotlin.logging)
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.jakarta.transaction.api)

    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
