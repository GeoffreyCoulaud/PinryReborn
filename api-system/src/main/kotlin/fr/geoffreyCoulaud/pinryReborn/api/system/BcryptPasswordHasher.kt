package fr.geoffreyCoulaud.pinryReborn.api.system

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import jakarta.enterprise.context.ApplicationScoped
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant

@ApplicationScoped
class BcryptPasswordHasher : PasswordHasher {
    override fun hash(raw: String, createdAt: Instant): HashedPassword =
        HashedPassword(
            hash = BCrypt.hashpw(raw, BCrypt.gensalt()),
            algorithm = PasswordHashAlgorithm.BCRYPT,
            createdAt = createdAt,
        )

    override fun matches(
        raw: String,
        stored: HashedPassword,
    ): Boolean =
        when (stored.algorithm) {
            PasswordHashAlgorithm.BCRYPT -> BCrypt.checkpw(raw, stored.hash)
        }
}
