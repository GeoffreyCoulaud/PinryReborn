package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models

import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.models.bases.AuditedBaseModel
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "user_password_hashes")
class UserPasswordHashModel(
    @ManyToOne var user: UserModel,
    var hash: String,
    @Enumerated(EnumType.STRING)
    var algorithm: PasswordHashAlgorithm,
) : AuditedBaseModel()
