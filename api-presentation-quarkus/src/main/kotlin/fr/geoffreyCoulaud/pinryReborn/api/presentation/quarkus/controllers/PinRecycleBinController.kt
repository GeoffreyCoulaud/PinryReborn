package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinRecycleBinSortStrategyInputEnum
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.PinMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.PinRecycleBinSortStrategyMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization.Base64Json
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinRecycleBin
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinRecycleBinGetter
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.jboss.resteasy.reactive.RestResponse
import java.util.UUID

@Path("/api/v1/pins/recycled")
class PinRecycleBinController(
    private val pinRecycleBin: PinRecycleBin,
    private val pinRecycleBinGetter: PinRecycleBinGetter,
    private val securityIdentity: SecurityIdentity,
) {
    @GET
    @Authenticated
    fun listRecycledPins(
        @QueryParam("cursor") @Base64Json cursorInput: CursorDto? = null,
        @QueryParam("pageSize") pageSizeInput: Int? = null,
        @QueryParam("sort") sortInput: PinRecycleBinSortStrategyInputEnum? = null,
    ): RestResponse<PinListOutputDto> {
        val user = securityIdentity.getUser()
        val pageSize = pageSizeInput ?: DEFAULT_PAGE_SIZE
        val sort = if (sortInput != null) sortInput.toDomain() else PinSortStrategy.DELETED_AT_DESC
        val cursor = cursorInput?.toDomain()

        return pinRecycleBinGetter
            .listSoftDeletedPinsPaginatedForUser(reader = user, cursor = cursor, pageSize = pageSize, sort = sort)
            .toDto()
            .let { RestResponse.ok(it) }
    }

    @POST
    @Authenticated
    @Path("/{pinId}/restore")
    fun restorePin(pinId: UUID): RestResponse<PinOutputDto> {
        val user = securityIdentity.getUser()
        return pinRecycleBin
            .restore(pinId = pinId, user = user)
            .toDto()
            .let { RestResponse.ok(it) }
    }

    @DELETE
    @Authenticated
    @Path("/{pinId}")
    fun permanentlyDeletePin(pinId: UUID): RestResponse<Void> {
        val user = securityIdentity.getUser()
        pinRecycleBin.permanentlyDelete(pinId = pinId, user = user)
        return RestResponse.noContent()
    }

    @DELETE
    @Authenticated
    fun emptyRecycleBin(): RestResponse<Void> {
        val user = securityIdentity.getUser()
        pinRecycleBin.emptyRecycleBin(user = user)
        return RestResponse.noContent()
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}
