package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDtoMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

@Path("/api/v1/me")
class MeController(
    private val securityIdentity: SecurityIdentity,
) {
    @GET
    @Authenticated
    fun getCurrentUser(): UserOutputDto = securityIdentity.getUser().toDto()
}
