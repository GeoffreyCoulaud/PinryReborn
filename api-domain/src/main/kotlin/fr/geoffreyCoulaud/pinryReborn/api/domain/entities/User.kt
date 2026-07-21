package fr.geoffreyCoulaud.pinryReborn.api.domain.entities

import java.util.UUID

data class User(
    override val id: UUID,
    val name: String,
    val softDeleted: Boolean = false,
) : Identifiable
