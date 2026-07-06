package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common.CursorDto
import fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.serialization.Base64JsonSerializer

data class PaginationOutputDto(
    @get:JsonSerialize(using = Base64JsonSerializer::class) val previousCursor: CursorDto?,
    @get:JsonSerialize(using = Base64JsonSerializer::class) val nextCursor: CursorDto?,
)
