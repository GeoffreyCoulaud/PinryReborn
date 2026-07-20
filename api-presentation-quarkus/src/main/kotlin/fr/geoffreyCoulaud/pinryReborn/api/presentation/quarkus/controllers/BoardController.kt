package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ApiConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.BoardInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinSortStrategyInputEnum
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.BoardMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.CursorMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.PinMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.PinSortStrategyMapper.toDomain
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization.Base64Json
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardCreator
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardGetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardPinLister
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardRecycleBin
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardUpdater
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.validation.Valid
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.net.URI
import java.util.UUID

@Path("/api/v1/boards")
class BoardController(
    private val boardCreator: BoardCreator,
    private val boardGetter: BoardGetter,
    private val boardUpdater: BoardUpdater,
    private val boardPinLister: BoardPinLister,
    private val boardRecycleBin: BoardRecycleBin,
    private val securityIdentity: SecurityIdentity,
    private val apiConfig: ApiConfig,
) {
    @POST
    @Authenticated
    fun createBoard(@Valid dto: BoardInputDto): RestResponse<BoardOutputDto> {
        val user = securityIdentity.getUser()
        val board = boardCreator.create(author = user, name = dto.name, description = dto.description)
        return ResponseBuilder
            .created<BoardOutputDto>(URI("${apiConfig.baseUrl()}/api/v1/boards/${board.id}"))
            .entity(board.toDto(pinCount = 0))
            .build()
    }

    @GET
    @Authenticated
    fun listBoards(): RestResponse<BoardListOutputDto> {
        val user = securityIdentity.getUser()
        val boards = boardGetter.listActiveBoardsForUser(user).map { board ->
            board.toDto(pinCount = boardGetter.countActivePinsForUserBoard(board.id, user))
        }
        return RestResponse.ok(BoardListOutputDto(boards = boards))
    }

    @GET
    @Authenticated
    @Path("/{boardId}")
    fun getBoard(boardId: UUID): RestResponse<BoardOutputDto> {
        val user = securityIdentity.getUser()
        val board = boardGetter.getActiveBoardForUser(boardId = boardId, reader = user)
        val count = boardGetter.countActivePinsForUserBoard(boardId, user)
        return RestResponse.ok(board.toDto(pinCount = count))
    }

    @PUT
    @Authenticated
    @Path("/{boardId}")
    fun updateBoard(boardId: UUID, @Valid dto: BoardInputDto): RestResponse<BoardOutputDto> {
        val user = securityIdentity.getUser()
        val board = boardUpdater.update(boardId = boardId, name = dto.name, description = dto.description, user = user)
        val count = boardGetter.countActivePinsForUserBoard(boardId, user)
        return RestResponse.ok(board.toDto(pinCount = count))
    }

    @DELETE
    @Authenticated
    @Path("/{boardId}")
    fun softDeleteBoard(boardId: UUID): RestResponse<Void> {
        val user = securityIdentity.getUser()
        boardRecycleBin.softDelete(boardId = boardId, user = user)
        return RestResponse.noContent()
    }

    @GET
    @Authenticated
    @Path("/{boardId}/pins")
    fun listBoardPins(
        boardId: UUID,
        @QueryParam("cursor") @Base64Json cursorInput: CursorDto? = null,
        @QueryParam("pageSize") pageSizeInput: Int? = null,
        @QueryParam("sort") sortInput: PinSortStrategyInputEnum? = null,
    ): RestResponse<PinListOutputDto> {
        val user = securityIdentity.getUser()
        val pageSize = pageSizeInput ?: DEFAULT_PAGE_SIZE
        val sort = if (sortInput != null) sortInput.toDomain() else PinSortStrategy.CREATED_AT_ASC
        val cursor = cursorInput?.toDomain()
        return boardPinLister
            .listActivePinsForBoard(reader = user, boardId = boardId, cursor = cursor, pageSize = pageSize, sort = sort)
            .toDto()
            .let { RestResponse.ok(it) }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}
