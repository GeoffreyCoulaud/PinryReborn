plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.noarg) apply false
    alias(libs.plugins.quarkus) apply false
    alias(libs.plugins.ebean) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
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

    // Branch-coverage gate (Kover). Applied to every module EXCEPT api-application,
    // which is the composition root + end-to-end tests and has no unit tests by design.
    // Coverage is measured per-module from that module's own tests (no aggregation):
    // integration tests in api-application must NOT count toward other modules.
    if (project.name != "api-application") {
        apply(plugin = "org.jetbrains.kotlinx.kover")

        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            reports {
                filters {
                    excludes {
                        // Ebean generated Kotlin query beans (kapt output). FQNs are
                        // `...models.query.Q<Entity>Model` (+ nested Assoc/AssocOne/AssocMany/
                        // Companion). Scoped to the query package so the pattern cannot match a
                        // hand-written `Q*`-named class elsewhere. (Also covered by the `models`
                        // package rule below; kept explicit as defense in depth.)
                        classes("fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.Q*")
                        // Other kapt-generated Ebean classes that don't match the `Q*` naming
                        // convention (e.g. EbeanEntityRegister). All Ebean querybean codegen
                        // carries this annotation (CLASS retention, readable by Kover's ASM-based
                        // filter). Found during calibration (Task 2).
                        annotatedBy("io.ebean.typequery.Generated")
                        // Ebean bytecode-enhancement rewrites entity classes in place (adds
                        // EntityBean-interface bookkeeping: _ebean_intercept, _ebean_get_id, a
                        // <clinit> building _ebean_props, etc). This injected bookkeeping cannot
                        // be distinguished from hand-written model code by class name or
                        // annotation (no marker at class or method level) and is frequently
                        // mis-attributed to the wrong source line by Kover's report. Operator
                        // decision B1 (calibration, Task 2 "KNOWN RISK"): exclude the whole
                        // `models` package (and its `models.bases` subpackage) from coverage.
                        // Harmless for other modules, which have no such package.
                        packages("fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models")
                    }
                }
                verify {
                    rule("100% branch coverage per package") {
                        groupBy = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.PACKAGE
                        bound {
                            coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                            minValue = 100
                        }
                    }
                }
            }
        }
    }
}
