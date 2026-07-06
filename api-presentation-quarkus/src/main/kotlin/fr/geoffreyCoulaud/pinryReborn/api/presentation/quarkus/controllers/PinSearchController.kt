package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinSearchOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SearchResultMapper.toPinSearchDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.usecases.PinSearcher
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.jboss.resteasy.reactive.RestResponse

@Path("/api/v1/pins")
class PinSearchController(
    private val pinSearcher: PinSearcher,
    private val securityIdentity: SecurityIdentity,
) {
    @GET
    @Authenticated
    @Path("/search")
    fun searchPins(
        @QueryParam("q") query: String?,
        @QueryParam("limit") limitParam: Int?,
    ): RestResponse<PinSearchOutputDto> {
        val user = securityIdentity.getUser()

        if (query.isNullOrBlank()) {
            return RestResponse.status(RestResponse.Status.BAD_REQUEST)
        }

        val limit = (limitParam ?: DEFAULT_LIMIT).coerceAtMost(MAX_LIMIT)

        return pinSearcher
            .searchPins(user = user, query = query, limit = limit)
            .toPinSearchDto()
            .let { RestResponse.ok(it) }
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 20
    }
}
