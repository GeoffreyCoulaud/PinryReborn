package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.enums.PasswordHashAlgorithm
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationUserDoesNotExistError
import jakarta.enterprise.context.ApplicationScoped
import org.mindrot.jbcrypt.BCrypt

@ApplicationScoped
class UserAuthenticator(
    private val userRepository: UserRepositoryInterface,
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
) {
    /**
     * Précalculé une fois. Sert à payer un coût BCrypt constant lorsque l'utilisateur
     * n'existe pas ou n'a pas de hash, afin d'éviter un oracle temporel (énumération).
     */
    private val dummyHash: String = BCrypt.hashpw("constant-time-guard", BCrypt.gensalt())

    fun authenticate(login: Login): User =
        when (login) {
            is BasicAuthLogin -> checkLogin(login)
        }

    private fun checkLogin(login: BasicAuthLogin): User {
        val user = userRepository.findUserByName(login.userName)
        val hash = user?.let { userPasswordRepository.findUserPasswordHash(it) }
        if (user == null || hash == null) {
            // Coût constant même sans utilisateur/hash : le résultat est ignoré.
            BCrypt.checkpw(login.password, dummyHash)
            throw if (user == null) {
                UserAuthenticationUserDoesNotExistError()
            } else {
                UserAuthenticationInvalidPasswordError()
            }
        }
        return user.takeIf { checkPassword(login.password, hash) }
            ?: throw UserAuthenticationInvalidPasswordError()
    }

    private fun checkPassword(
        received: String,
        stored: HashedPassword,
    ): Boolean {
        when (stored.algorithm) {
            PasswordHashAlgorithm.BCRYPT -> return BCrypt.checkpw(received, stored.hash)
        }
    }
}
