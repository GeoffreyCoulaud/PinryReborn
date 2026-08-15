package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PinSortStrategy
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.config.ApiConfig
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.BoardInputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.input.PinSortStrategyInputEnum
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.PinListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.ProblemDetail
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
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder
import java.net.URI
import java.util.UUID

@Path("/api/v1/boards")
@Suppress("LongParameterList") // CDI-injected: every parameter is a collaborator provided by the container.
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
    // SmallRye reads the status off the return type, and a runtime ResponseBuilder carries none, so
    // the 201 is declared with the 409 the name constraint answers (spec 2026-08-14 section 12).
    @APIResponse(
        responseCode = "201",
        description = "Board created",
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = Schema(implementation = BoardOutputDto::class),
            ),
        ],
    )
    @APIResponse(
        responseCode = "409",
        description = BOARD_NAME_ALREADY_EXISTS,
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
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
    @APIResponse(
        responseCode = "200",
        description = "Board updated",
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = Schema(implementation = BoardOutputDto::class),
            ),
        ],
    )
    @APIResponse(
        responseCode = "409",
        description = BOARD_NAME_ALREADY_EXISTS,
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
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

        private const val PROBLEM_JSON = "application/problem+json"
        private const val BOARD_NAME_ALREADY_EXISTS =
            "BOARD_NAME_ALREADY_EXISTS: this account already holds a board of that name, ASCII case " +
                "folded, and a recycled board holds its name until the bin is emptied"
    }
}
