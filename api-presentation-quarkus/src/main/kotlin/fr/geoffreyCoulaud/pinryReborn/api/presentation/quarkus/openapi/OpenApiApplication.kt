package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.openapi

import jakarta.ws.rs.core.Application
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme

/**
 * Declares the Bearer security scheme referenced by every protected endpoint.
 *
 * Quarkus's SmallRye OpenAPI extension auto-adds a `security: [{"SecurityScheme": []}]` requirement
 * to every `@Authenticated` / `@RolesAllowed` operation (name from the default
 * `quarkus.smallrye-openapi.security-scheme-name`), but does not auto-populate the matching
 * `components.securitySchemes` entry unless a built-in mechanism (Basic, JWT, OAuth2, OIDC) is
 * detected. Session tokens are opaque bearer tokens, not JWTs, so the config-based
 * `quarkus.smallrye-openapi.security-scheme=jwt` shortcut would mislabel them (it forces
 * `bearerFormat: JWT`). Declaring the scheme explicitly keeps the generated `docs/openapi.json`
 * coherent (no dangling security refs) while accurately documenting `Authorization: Bearer <token>`.
 *
 * Quarkus does not require a JAX-RS `Application` subclass, but application-level OpenAPI
 * annotations (like `@SecurityScheme`) need one as their scan anchor. This class carries no
 * behavior beyond that; declaring it does not change routing (no `@ApplicationPath` is set, so the
 * application path stays the default "/").
 */
@SecurityScheme(
    securitySchemeName = "SecurityScheme",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    description = "Session token issued by POST /api/v1/sessions, sent as 'Authorization: Bearer <token>'.",
)
class OpenApiApplication : Application()
