rootProject.name = "pinry-reborn"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":api-domain")
include(":api-usecases")
include(":api-persistence-sqlite")
include(":api-presentation-quarkus")
include(":api-application")
include(":api-utilities")
include(":api-storage-filesystem")
include(":api-imaging-vips")
include(":api-fetch-http")
include(":api-system")
include(":api-worker-quarkus")
include(":detekt-rules")
