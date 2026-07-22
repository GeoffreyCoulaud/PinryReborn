package fr.geoffreyCoulaud.pinryReborn.api.system

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import jakarta.enterprise.context.ApplicationScoped
import org.mindrot.jbcrypt.BCrypt

@ApplicationScoped
class BcryptPasswordHasher : PasswordHasher {
    override fun hash(raw: String): HashedPassword =
        HashedPassword(hash = BCrypt.hashpw(raw, BCrypt.gensalt()), algorithm = PasswordHashAlgorithm.BCRYPT)

    override fun matches(
        raw: String,
        stored: HashedPassword,
    ): Boolean =
        when (stored.algorithm) {
            PasswordHashAlgorithm.BCRYPT -> BCrypt.checkpw(raw, stored.hash)
        }
}
