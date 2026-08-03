package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.UserModel
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.query.QUserModel
import fr.geoffreyCoulaud.pinryReborn.api.utilities.createRandomString
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EbeanPersistorTest : RepositoryTest() {
    // Exercising EbeanPersistor through the Persistor port exposed by RepositoryTest: the base
    // wires EbeanPersistor(database), so the concrete adapter under test is the one each method
    // dispatches to, observed through the narrower surface a holder of Persistor can reach.

    @Test
    fun `Given a model, Then save persists it`() {
        // Given
        val model = newUser()

        // When
        persistor.save(model)

        // Then
        assertNotNull(QUserModel().id.equalTo(model.id).findOne())
    }

    @Test
    fun `Given a persisted model, Then delete removes it`() {
        // Given
        val model = newUser()
        persistor.save(model)

        // When
        persistor.delete(model)

        // Then
        assertNull(QUserModel().id.equalTo(model.id).findOne())
    }

    @Test
    fun `Given a modified model, Then merge writes the change`() {
        // Given
        val model = newUser()
        persistor.save(model)
        model.name = "renamed"

        // When
        persistor.merge(model)

        // Then
        assertEquals("renamed", QUserModel().id.equalTo(model.id).findOne()?.name)
    }

    @Test
    fun `Given a type and id, Then reference returns a usable proxy`() {
        // Given
        val model = newUser()
        persistor.save(model)

        // When
        val reference = persistor.reference(UserModel::class.java, model.id)

        // Then
        assertEquals(model.id, reference.id)
    }

    // UserModel is the root entity (no foreign key), so it is the simplest model to persist directly.
    private fun newUser(): UserModel =
        UserModel(id = UUID.randomUUID(), name = createRandomString(), createdAt = storableNow())
}
