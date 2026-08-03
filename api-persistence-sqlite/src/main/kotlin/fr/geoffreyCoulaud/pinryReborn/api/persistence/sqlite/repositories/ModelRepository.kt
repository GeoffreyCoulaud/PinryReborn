package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel

internal class ModelRepository<T : BaseModel>(
    private val persistor: Persistor,
) {
    fun saveAndReturn(model: T): T = model.also { persistor.merge(it) }
}
