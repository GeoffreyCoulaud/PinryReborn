package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.UserInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.UserOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.UserDtoMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.usecases.UserCreator
import jakarta.annotation.security.PermitAll
import jakarta.validation.Valid
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.jboss.resteasy.reactive.RestResponse

@Path("/api/v1/users")
class UserController(
    private val userCreator: UserCreator,
) {
    @POST
    @PermitAll
    fun createUser(@Valid userDto: UserInputDto): RestResponse<UserOutputDto> {
        val userOutputDto = userCreator.createUserWithPassword(name = userDto.name, password = userDto.password).toDto()
        return RestResponse.ok(userOutputDto)
    }
}
