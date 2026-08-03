package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.Persistor
import io.ebean.Database
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class EbeanPersistor(
    private val database: Database,
) : Persistor {
    override fun save(bean: Any) {
        database.save(bean)
    }

    override fun delete(bean: Any) {
        database.delete(bean)
    }

    override fun merge(bean: Any) {
        database.merge(bean)
    }

    override fun <T : Any> reference(type: Class<T>, id: Any): T {
        return database.reference(type, id)
    }
}
