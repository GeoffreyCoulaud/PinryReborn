package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.common

import java.util.UUID

class CursorDto(
    val pivotId: UUID,
    val direction: CursorDirectionDto,
)
