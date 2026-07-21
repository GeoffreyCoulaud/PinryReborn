package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.SessionCreationInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.CreatedSessionOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ExistingSessionOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SessionDtoMapper.toCreatedDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SessionDtoMapper.toExistingDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getSessionToken
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRenewer
import fr.geoffreyCoulaud.pinryReborn.api.usecases.SessionRevoker
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.SessionExpiryPolicy
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationError
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.validation.Valid
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.jboss.resteasy.reactive.ResponseStatus

@Path("/api/v1/sessions")
class SessionController(
    private val sessionCreator: SessionCreator,
    private val sessionRenewer: SessionRenewer,
    private val sessionRevoker: SessionRevoker,
    private val expiryPolicy: SessionExpiryPolicy,
    private val securityIdentity: SecurityIdentity,
) {
    @POST
    @PermitAll
    @ResponseStatus(HTTP_CREATED)
    fun createSession(@Valid dto: SessionCreationInputDto): CreatedSessionOutputDto {
        val issued = try {
            sessionCreator.create(name = dto.name, password = dto.password, persistent = dto.rememberMe ?: false)
        } catch (e: UserAuthenticationError) {
            throw AuthenticationFailedException("Authentication failed", e)
        }
        return issued.toCreatedDto()
    }

    @GET
    @Path("/current")
    @Authenticated
    fun getCurrentSession(): ExistingSessionOutputDto {
        val current = securityIdentity.getSessionToken()
        return current.toExistingDto(expiryPolicy.renewAfterFor(current.expiresAt, current.persistent))
    }

    @POST
    @Path("/current/renew")
    @Authenticated
    fun renewSession(): CreatedSessionOutputDto =
        sessionRenewer.renew(securityIdentity.getSessionToken()).toCreatedDto()

    @DELETE
    @Path("/current")
    @Authenticated
    fun revokeCurrentSession() = sessionRevoker.revokeCurrent(securityIdentity.getSessionToken())

    @DELETE
    @Authenticated
    fun revokeAllSessions() = sessionRevoker.revokeAll(securityIdentity.getUser())

    private companion object {
        const val HTTP_CREATED = 201
    }
}
