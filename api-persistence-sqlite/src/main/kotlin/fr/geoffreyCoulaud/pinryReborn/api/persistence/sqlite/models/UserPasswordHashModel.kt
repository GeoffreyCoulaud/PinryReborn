package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.BaseModel
import io.ebean.annotation.Index
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "user_password_hashes")
@Index(name = "ix_user_password_hashes_user_created", columnNames = ["user_id", "when_created"], unique = true)
class UserPasswordHashModel(
    @ManyToOne var user: UserModel,
    var hash: String,
    @Enumerated(EnumType.STRING)
    var algorithm: PasswordHashAlgorithm,
    // Reuses the historical `when_created` column: the property is now mapper-written from the
    // domain `createdAt` the use case stamps, no longer auto-stamped (D19).
    @Column(name = "when_created") var createdAt: Instant,
) : BaseModel()
