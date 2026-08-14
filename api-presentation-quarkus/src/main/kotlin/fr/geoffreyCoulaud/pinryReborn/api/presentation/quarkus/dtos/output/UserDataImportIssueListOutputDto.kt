package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.dtos.output

data class UserDataImportIssueListOutputDto(
    val issues: List<UserDataImportIssueOutputDto>,
    val pagination: PaginationOutputDto,
)
