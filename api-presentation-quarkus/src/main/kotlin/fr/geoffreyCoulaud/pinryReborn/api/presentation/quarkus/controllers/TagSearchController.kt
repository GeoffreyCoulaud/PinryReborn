package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.TagSearchOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.SearchResultMapper.toTagSearchDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.usecases.TagSearcher
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.validation.constraints.NotBlank
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.jboss.resteasy.reactive.RestResponse

@Path("/api/v1/tags")
class TagSearchController(
    private val tagSearcher: TagSearcher,
    private val securityIdentity: SecurityIdentity,
) {
    @GET
    @Authenticated
    @Path("/search")
    fun searchTags(
        @QueryParam("q") @NotBlank query: String?,
        @QueryParam("limit") limitParam: Int?,
    ): RestResponse<TagSearchOutputDto> {
        val user = securityIdentity.getUser()
        val limit = (limitParam ?: DEFAULT_LIMIT).coerceAtMost(MAX_LIMIT)

        return tagSearcher
            .searchTags(user = user, query = requireNotNull(query), limit = limit)
            .toTagSearchDto()
            .let { RestResponse.ok(it) }
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 20
    }
}
