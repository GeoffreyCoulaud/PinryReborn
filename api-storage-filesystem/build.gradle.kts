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
    // Read side only: it is what makes a null in a non-nullable property a parse failure. It lands on
    // the writer's classpath too, which the export mapper's module-id assertion is there to hold.
    implementation(libs.jackson.module.kotlin)

    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
