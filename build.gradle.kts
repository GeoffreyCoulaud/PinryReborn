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
    apply(plugin = "dev.detekt")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }
        // Toolchain and bytecode target are both JDK 25 (no split): detekt 2.0
        // runs and analyses on JDK 25, so the old Java-21 floor (forced by detekt
        // 1.23.8's --jvm-target 22 cap) is gone. A 25 target is also required to
        // consume vips-ffm, whose Gradle metadata declares org.gradle.jvm.version 22
        // (a Java-21 consumer variant is rejected); vips-ffm additionally needs a
        // JDK 23+ runtime, which the toolchain satisfies. Keep compileJava consistent
        // with compileKotlin (Kotlin enforces matching JVM targets).
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            // Bytecode target matches the JDK 25 toolchain (see the Java note above).
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
            javaParameters.set(true)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // sqlite-jdbc loads a native library via the restricted System::load; on JDK 25
        // this warns ("Restricted methods will be blocked in a future release unless
        // native access is enabled"). Grant it explicitly so tests run clean and stay
        // forward-compatible. (The runtime image passes the same flag; see Dockerfile.)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    // vips-ffm (api-imaging-vips, api-application) resolves libvips/glib/gobject via
    // DYLD_LIBRARY_PATH on macOS, but SIP strips DYLD_* from the signed Adoptium JDK, so
    // homebrew's libs are invisible to it. Point vips-ffm at the dylibs explicitly; a no-op
    // on Linux (CI) where these paths do not exist.
    //
    // Applied in afterEvaluate because the plain tasks.withType<Test> block above does NOT
    // reach the api-application (Quarkus) test JVM: verified for both the systemProperty and
    // jvmArgs forms, the -D is absent from that JVM's command line and the imaging tests fail
    // with UnsatisfiedLinkError. Set in afterEvaluate (after the Quarkus plugin configures the
    // task) the -D is present and they pass. The precise Quarkus internal is not load-bearing
    // for the fix; the observation is.
    afterEvaluate {
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            val homebrewLib = listOf("/opt/homebrew/lib", "/usr/local/lib")
                .firstOrNull { java.io.File(it).isDirectory }
            if (homebrewLib != null) {
                tasks.withType<Test> {
                    mapOf(
                        "vips" to "libvips.dylib",
                        "glib" to "libglib-2.0.dylib",
                        "gobject" to "libgobject-2.0.dylib",
                    ).forEach { (lib, name) ->
                        jvmArgs("-Dvipsffm.libpath.$lib.override=$homebrewLib/$name")
                    }
                }
            }
        }
    }

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
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

    // Analyse against the JDK 25 bytecode target (matches the Kotlin jvmTarget above);
    // detekt 2.0 runs and analyses on the JDK 25 toolchain.
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        jvmTarget = "25"
    }

    // Branch-coverage gate (Kover). Applied to every module EXCEPT api-application,
    // which is the composition root + end-to-end tests and has no unit tests by design.
    // Two grains, easy to conflate: coverage is MEASURED per-module from that module's own
    // tests (no aggregation, so api-application's integration tests must NOT count toward
    // other modules), while the 100% bound is VERIFIED per package (see the rule below), so
    // a module averaging 100% still fails when one of its packages does not.
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
