package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.controllers

import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.BoardOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output.RecycledBoardListOutputDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.BoardMapper.toDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.mappers.BoardMapper.toRecycledDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security.getUser
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardGetter
import fr.geoffreyCoulaud.pinryReborn.api.usecases.BoardRecycleBin
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.jboss.resteasy.reactive.RestResponse
import java.util.UUID

@Path("/api/v1/boards/recycled")
class BoardRecycleBinController(
    private val boardRecycleBin: BoardRecycleBin,
    private val boardGetter: BoardGetter,
    private val securityIdentity: SecurityIdentity,
) {
    @GET
    @Authenticated
    fun listRecycledBoards(): RestResponse<RecycledBoardListOutputDto> {
        val user = securityIdentity.getUser()
        val boards = boardRecycleBin.listRecycledBoardsForUser(user).map { it.toRecycledDto() }
        return RestResponse.ok(RecycledBoardListOutputDto(boards))
    }

    @POST
    @Authenticated
    @Path("/{boardId}/restore")
    fun restoreBoard(boardId: UUID): RestResponse<BoardOutputDto> {
        val user = securityIdentity.getUser()
        val board = boardRecycleBin.restore(boardId = boardId, user = user)
        val count = boardGetter.countActivePinsForUserBoard(board.id, user)
        return RestResponse.ok(board.toDto(pinCount = count))
    }

    @DELETE
    @Authenticated
    @Path("/{boardId}")
    fun permanentlyDeleteBoard(boardId: UUID): RestResponse<Void> {
        val user = securityIdentity.getUser()
        boardRecycleBin.permanentlyDelete(boardId = boardId, user = user)
        return RestResponse.noContent()
    }

    @DELETE
    @Authenticated
    fun emptyRecycleBin(): RestResponse<Void> {
        val user = securityIdentity.getUser()
        boardRecycleBin.emptyRecycleBin(user = user)
        return RestResponse.noContent()
    }
}
