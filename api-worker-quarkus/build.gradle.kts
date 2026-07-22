plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.jandex)
}

allOpen {
    annotation("jakarta.enterprise.context.ApplicationScoped")
}

dependencies {
    implementation(project(":api-domain"))
    implementation(project(":api-usecases"))
    implementation(libs.kotlin.logging)
    implementation(libs.smallrye.config)

    compileOnly(platform(libs.quarkus.bom))
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.quarkus.core)
    compileOnly(libs.quarkus.micrometer)

    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(platform(libs.quarkus.bom))
    testImplementation(libs.jakarta.cdi.api)
    testImplementation(libs.quarkus.core)
    testImplementation(libs.quarkus.micrometer)
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
