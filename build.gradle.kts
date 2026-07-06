plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.noarg) apply false
    alias(libs.plugins.quarkus) apply false
    alias(libs.plugins.ebean) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "fr.geoffreyCoulaud.pinryReborn"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        mavenLocal()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            javaParameters.set(true)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom("$rootDir/config/detekt/detekt.yml")
        // Baselines are per-module: a single shared file cannot work because each
        // module's detektBaseline task rewrites (does not merge) the target file.
        // The path degrades gracefully when the file is absent (no baseline applied).
        baseline = file("$rootDir/config/detekt/baseline-${project.name}.xml")
        // Also analyse the java-test-fixtures source set (used by api-utilities)
        // in addition to detekt's default main/test source directories.
        source.from("src/testFixtures/kotlin")
    }
}
