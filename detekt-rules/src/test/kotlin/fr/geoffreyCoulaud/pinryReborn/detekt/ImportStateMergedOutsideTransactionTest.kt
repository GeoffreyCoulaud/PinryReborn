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
            "This save merges a row read elsewhere, which restores every column that row carried, " +
                "its state included. Read the row inside the transaction that saves it.",
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
    fun `Given a save naming no state, Then it is reported all the same`() {
        // Given: the chunk receiver's shape. `merge` writes every column, so a copy restores the state
        // whether it names it or not: two counters were enough to bring back an AWAITING_ARCHIVE
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

        // Then: the positional copy too, which names nothing at all
        assertEquals(2, findings.size)
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
    fun `Given a row the save did not build itself, Then it is reported however it got there`() {
        // Given: the three indirections the copy-shaped rule missed, a named local, a scoping function
        // around the copy and one around the save. The first is the abandonment sweep's natural shape.
        val code =
            """
            class Sweep {
                fun named(current: UserDataImport) {
                    val abandoned = current.copy(state = UserDataImportState.ABANDONED)
                    repository.save(abandoned)
                }

                fun alsoed(row: UserDataImport) =
                    repository.save(row.copy(state = UserDataImportState.ABANDONED).also { log(it) })

                fun letted(row: UserDataImport) =
                    row.copy(state = UserDataImportState.ABANDONED).let { repository.save(it) }

                fun asRead(row: UserDataImport) = repository.save(row)
            }
            """.trimIndent()

        // When
        val findings = rule.lint(code)

        // Then
        assertEquals(4, findings.size)
    }

    @Test
    fun `Given a save handed no argument at all, Then nothing is reported`() {
        // Given: nothing names a row, so nothing says a row was read elsewhere
        val code =
            """
            class Fence {
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
