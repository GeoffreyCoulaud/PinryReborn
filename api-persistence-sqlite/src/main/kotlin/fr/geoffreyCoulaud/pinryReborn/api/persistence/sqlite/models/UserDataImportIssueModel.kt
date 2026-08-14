package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Entity
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "user_data_import_issues")
// Declared, not inherited: Ebean writes the foreign key without an index behind it, and every read of
// this table is by import (the report, the cap's count, and both deletion paths).
@Index(name = "ix_user_data_import_issues_import", columnNames = ["import_id"])
class UserDataImportIssueModel(
    id: UUID,
    // `import` is a Kotlin keyword, so the field is named around it and the column named back.
    @ManyToOne @JoinColumn(name = "import_id") var userDataImport: UserDataImportModel,
    var kind: String,
    var line: Int? = null,
    var subject: String? = null,
    var detail: String? = null,
) : BaseModel(id = id)
