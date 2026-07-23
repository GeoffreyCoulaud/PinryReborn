package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

data class UserDataExportListOutputDto(
    val exports: List<UserDataExportOutputDto>,
    val pagination: PaginationOutputDto,
)
