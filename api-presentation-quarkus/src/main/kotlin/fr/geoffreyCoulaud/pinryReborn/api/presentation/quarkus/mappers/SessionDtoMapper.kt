package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.IssuedSession
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.CreatedSessionOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ExistingSessionOutputDto
import java.time.Instant

object SessionDtoMapper {
    fun IssuedSession.toCreatedDto() =
        CreatedSessionOutputDto(token = token, expiresAt = expiresAt, renewAfter = renewAfter)

    fun SessionToken.toExistingDto(renewAfter: Instant) =
        ExistingSessionOutputDto(expiresAt = expiresAt, renewAfter = renewAfter, persistent = persistent)
}
