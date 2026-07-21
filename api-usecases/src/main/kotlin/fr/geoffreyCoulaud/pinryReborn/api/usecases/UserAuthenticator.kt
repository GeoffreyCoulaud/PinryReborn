package fr.geoffreyCoulaud.pinryReborn.api.usecases

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.HashedPassword
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.Login.BasicAuthLogin
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserPasswordHashRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.repositories.UserRepositoryInterface
import fr.geoffreyCoulaud.pinryReborn.api.domain.security.PasswordHasher
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationInvalidPasswordError
import fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions.UserAuthenticationUserDoesNotExistError
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class UserAuthenticator(
    private val userRepository: UserRepositoryInterface,
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
) {
    /**
     * Precomputed once. Pays a constant hashing cost when the user does not exist or has no
     * stored hash, to avoid a timing oracle (username enumeration).
     */
    private val dummyHash: HashedPassword by lazy { passwordHasher.hash("constant-time-guard") }

    fun authenticate(login: Login): User =
        when (login) {
            is BasicAuthLogin -> checkLogin(login)
        }

    private fun checkLogin(login: BasicAuthLogin): User {
        val user = userRepository.findUserByName(login.userName)
        val hash = user?.let { userPasswordRepository.findCurrentPasswordHash(it) }
        if (user == null || hash == null) {
            // Constant cost even without a user/hash: the result is ignored.
            passwordHasher.matches(login.password, dummyHash)
            throw if (user == null) {
                UserAuthenticationUserDoesNotExistError()
            } else {
                UserAuthenticationInvalidPasswordError()
            }
        }
        return user.takeIf { passwordHasher.matches(login.password, hash) }
            ?: throw UserAuthenticationInvalidPasswordError()
    }
}
