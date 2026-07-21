package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PasswordChangeInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDtoMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PasswordChanger
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.validation.Valid
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import org.jboss.resteasy.reactive.RestResponse

@Path("/api/v1/me")
class MeController(
    private val securityIdentity: SecurityIdentity,
    private val passwordChanger: PasswordChanger,
) {
    @GET
    @Authenticated
    fun getCurrentUser(): UserOutputDto = securityIdentity.getUser().toDto()

    @PUT
    @Path("/password")
    @Authenticated
    fun changePassword(@Valid dto: PasswordChangeInputDto): RestResponse<Void> {
        passwordChanger.changePassword(securityIdentity.getUser(), dto.currentPassword, dto.newPassword)
        return RestResponse.noContent()
    }
}
