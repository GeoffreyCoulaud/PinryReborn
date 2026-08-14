package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

data class UserDataImportListOutputDto(
    val imports: List<UserDataImportOutputDto>,
    val pagination: PaginationOutputDto,
)
