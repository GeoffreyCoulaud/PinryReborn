package fr.geoffreyCoulaud.pinryReborn.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImportStateMergedOutsideTransactionTest {
    private val rule = ImportStateMergedOutsideTransaction(Config.empty)

    @Test
    fun `Given a state transition saved on a copy read elsewhere, Then it is reported`() {
        // Given: the shape every site of this defect took, a row read before the write and merged back
        val code =
            """
            class Canceller {
                fun cancel(userDataImport: UserDataImport) {
                    repository.save(userDataImport.copy(state = UserDataImportState.CANCELLED))
                }
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then: the save is what is reported, so a rule reporting the copy or the line's other call
        // fails here
        val finding = findings.single()
        assertEquals(3, finding.entity.location.source.line)
        assertEquals(
            "This save writes a state transition onto a copy of a row read elsewhere, which restores " +
                "every column that copy carried. Write it through saveFenced, which reads the row " +
                "inside the transaction that saves it.",
            finding.message,
        )
    }

    @Test
    fun `Given the same write inside the transaction that saves it, Then nothing is reported`() {
        // Given: the fence, where the read and the write are one pair
        val code =
            """
            class Runner {
                fun advance(importId: UUID) =
                    transactionRunner.inTransaction {
                        repository.findById(importId)?.let {
                            repository.save(it.copy(state = UserDataImportState.RUNNING))
                        }
                    }
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a save that writes no state, Then nothing is reported`() {
        // Given: the milder half of the same cause, out of this rule's reach deliberately: a stale
        // counter mis-reports, while a stale state makes the next actor act on it
        val code =
            """
            class Receiver {
                fun receive(userDataImport: UserDataImport, uploadedBytes: Long) =
                    repository.save(userDataImport.copy(uploadedBytes = uploadedBytes))

                fun stamp(userDataImport: UserDataImport, activity: Instant) =
                    repository.save(userDataImport.copy(activity))
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a save that builds the row rather than copying one, Then nothing is reported`() {
        // Given: an insert has no earlier state to restore, and the index is the authority on it
        val code =
            """
            class Creator {
                fun create(user: User) =
                    repository.save(UserDataImport(id = randomUUID(), state = AWAITING_ARCHIVE))
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a save handed a row it did not copy, Then nothing is reported`() {
        // Given: the fence's own write, whose argument is neither a copy nor a construction
        val code =
            """
            class Fence {
                fun saveFenced(row: UserDataImport, update: (UserDataImport) -> UserDataImport) =
                    save(update(row))

                fun saveAsRead(row: UserDataImport) = save(row)

                fun saveNothing() = save()
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a state transition passed to something other than a save, Then nothing is reported`() {
        // Given: building the transition is not writing it; the runner builds one in a named helper
        // that the fence then calls with the row it read
        val code =
            """
            class Runner {
                fun failed(current: UserDataImport, failureCode: String) =
                    current.copy(state = UserDataImportState.FAILED, failureCode = failureCode)

                fun report(current: UserDataImport) =
                    recorder.record(current.copy(state = UserDataImportState.FAILED))
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(0, findings.size)
    }

    @Test
    fun `Given a state transition copied without a receiver, Then it is reported`() {
        // Given: inside a scoping function the copy loses its receiver, and it is the same act
        val code =
            """
            class Canceller {
                fun cancel(userDataImport: UserDataImport) =
                    with(userDataImport) { repository.save(copy(state = UserDataImportState.CANCELLED)) }
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(1, findings.size)
    }
}
