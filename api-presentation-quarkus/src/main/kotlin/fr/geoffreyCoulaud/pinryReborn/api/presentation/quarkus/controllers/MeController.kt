package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PasswordChangeInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDtoMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.ReauthenticationHeader
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.usecases.AccountDeleter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PasswordChanger
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.validation.Valid
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.jboss.resteasy.reactive.RestResponse

@Path("/api/v1/me")
class MeController(
    private val securityIdentity: SecurityIdentity,
    private val passwordChanger: PasswordChanger,
    private val accountDeleter: AccountDeleter,
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

    @DELETE
    @Authenticated
    @APIResponse(responseCode = "202", description = "Account deletion accepted")
    fun deleteAccount(@HeaderParam(ReauthenticationHeader.HEADER) reauthHeader: String?): RestResponse<Void> {
        val factor = ReauthenticationHeader.parsePasswordFactor(reauthHeader)
        accountDeleter.requestDeletion(securityIdentity.getUser(), factor)
        return RestResponse.ResponseBuilder.create<Void>(RestResponse.Status.ACCEPTED).build()
    }
}
