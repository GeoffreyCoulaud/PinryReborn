package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.repositories

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.RepositoryTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class EbeanTransactionControlTest : RepositoryTest() {

    @Test
    fun `Given no ambient transaction, Then currentTransaction is null`() {
        // Given no transaction opened on this thread
        // When
        val current = transactionControl.currentTransaction()

        // Then
        assertNull(current)
    }

    @Test
    fun `Given beginTransaction, Then it returns a transaction that currentTransaction sees`() {
        // Given
        transactionControl.beginTransaction().use { transaction ->
            // When
            val current = transactionControl.currentTransaction()

            // Then
            assertNotNull(current)
            transaction.commit()
        }
    }
}
