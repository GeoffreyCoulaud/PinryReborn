plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.jandex)
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
}

dependencies {
    implementation(project(":api-domain"))
    implementation(project(":api-usecases"))
    implementation(project(":api-utilities"))

    implementation(libs.kotlin.logging)
    implementation(libs.smallrye.config)

    // Quarkus APIs - provided by Quarkus at runtime
    compileOnly(platform(libs.quarkus.bom))
    compileOnly(libs.bundles.quarkus.compileOnly)
    compileOnly(libs.quarkus.security)
    compileOnly(libs.quarkus.smallrye.openapi)
    compileOnly(libs.quarkus.hibernate.validator)

    // Tests
    testImplementation(testFixtures(project(":api-utilities")))
    testImplementation(platform(libs.quarkus.bom))
    testImplementation(libs.jakarta.ws.rs.api)
    testImplementation(libs.resteasy.reactive.common)
    testImplementation(libs.resteasy.reactive)
    testImplementation(libs.quarkus.security)
    // Needed to unit-test ObjectMapper-consuming collaborators (LoggingRequestResponseFilter,
    // Base64JsonSerializer, Base64JsonParamConverter*) — compileOnly at main scope, not
    // inherited by the test source set.
    testImplementation(libs.jackson.databind)
    // Transitively provides io.vertx.* and io.quarkus.vertx.http.* on the test classpath, needed to
    // compile the Bearer auth mechanism tests (BearerAuthenticationMechanism extends
    // HttpAuthenticationMechanism; the tests mock RoutingContext/HttpServerRequest). On main these
    // come via quarkus-smallrye-openapi, which the test source set does not depend on.
    testImplementation(libs.quarkus.micrometer)
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.bundles.testing.runtime)
}
