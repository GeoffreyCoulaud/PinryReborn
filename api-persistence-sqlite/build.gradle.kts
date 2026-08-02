plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.ebean)
    alias(libs.plugins.jandex)
}

dependencies {
    implementation(project(":api-domain"))
    implementation(project(":api-utilities"))

    implementation(libs.kotlin.logging)

    // Ebean ORM
    implementation(libs.ebean)
    implementation(libs.ebean.sqlite)
    implementation(libs.ebean.ddl.generator)
    implementation(libs.ebean.migration)
    kapt(libs.ebean.querybean.generator)

    // SQLite JDBC driver
    implementation(libs.sqlite.jdbc)

    // CDI API for annotations - implementation provided by Quarkus at runtime
    compileOnly(libs.jakarta.cdi.api)

    // MicroProfile Config API (@ConfigProperty) - implementation provided by Quarkus at runtime
    compileOnly(libs.microprofile.config.api)

    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(libs.ebean.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.konsist)
    testRuntimeOnly(libs.bundles.testing.runtime)
}

ebean {
    debugLevel = 1
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

noArg {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.register<JavaExec>("generateDbMigration") {
    group = "ebean"
    description = "Generate Ebean database migration"
    mainClass.set("fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.migration.GenerateDbMigrationKt")
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn("compileTestKotlin")
}
