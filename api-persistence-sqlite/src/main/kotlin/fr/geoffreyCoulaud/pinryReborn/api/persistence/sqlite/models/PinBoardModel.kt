package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import jakarta.persistence.Entity
import jakarta.persistence.ManyToOne

/**
 * Many-to-many join between pins and boards.
 * Kept as a standalone join entity (like PinTagModel) to avoid interdependency of models and repos.
 */
@Entity
class PinBoardModel(
    @ManyToOne var pin: PinModel,
    @ManyToOne var board: BoardModel,
)
