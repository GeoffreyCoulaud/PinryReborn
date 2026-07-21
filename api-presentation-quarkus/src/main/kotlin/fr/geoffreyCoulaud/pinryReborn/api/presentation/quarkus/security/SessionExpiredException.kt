package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import io.quarkus.security.AuthenticationCompletionException

/**
 * A structurally valid but expired bearer token.
 *
 * [io.quarkus.security.AuthenticationFailedException] is `final` in this project's resolved Quarkus
 * security version, so it cannot be subclassed. [AuthenticationCompletionException] is the closest
 * non-final sibling: it also implements Quarkus's `AuthenticationException` marker interface and is
 * recognized by the security pipeline's default auth-failure handler as a 401, while a dedicated
 * mapper renders the distinct SESSION_EXPIRED code (and the Bearer challenge) on top of that.
 */
class SessionExpiredException(message: String, cause: Throwable? = null) :
    AuthenticationCompletionException(message, cause)
