plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.quarkus)
}

dependencies {
    // All modules
    implementation(project(":api-domain"))
    implementation(project(":api-usecases"))
    implementation(project(":api-persistence-sqlite"))
    implementation(project(":api-presentation-quarkus"))

    // BOM
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Quarkus
    implementation(libs.bundles.quarkus.runtime)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.kotlin.stdlib)

    // Logging
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.jboss.logmanager)

    // Integration testing
    testImplementation(libs.bundles.integration.testing)
    testImplementation(libs.ebean)
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}
