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
import java.time.Instant

@ApplicationScoped
class UserAuthenticator(
    private val userRepository: UserRepositoryInterface,
    private val userPasswordRepository: UserPasswordHashRepositoryInterface,
    private val passwordHasher: PasswordHasher,
    private val attemptLimiter: AuthenticationAttemptLimiter,
) {
    /**
     * Precomputed once. Pays a constant hashing cost when the user does not exist or has no
     * stored hash, to avoid a timing oracle (username enumeration).
     */
    // Never persisted and never read: a placeholder instant stands in for a Clock this class has no other use for.
    private val dummyHash: HashedPassword by lazy {
        passwordHasher.hash("constant-time-guard", Instant.EPOCH)
    }

    fun authenticate(login: Login): User =
        when (login) {
            is BasicAuthLogin -> checkLogin(login)
        }

    private fun checkLogin(login: BasicAuthLogin): User {
        // Keyed by the submitted name, and checked before anything is hashed, so a blocked name
        // costs no bcrypt (spec D2 and D6).
        val attemptKey = AuthenticationAttemptKey.forLogin(login.userName)
        attemptLimiter.check(attemptKey)
        val user = userRepository.findUserByName(login.userName)
        val hash = user?.let { userPasswordRepository.findCurrentPasswordHash(it) }
        if (user == null || hash == null) {
            // Constant cost even without a user/hash: the result is ignored.
            passwordHasher.matches(login.password, dummyHash)
            // Counted like a wrong password: a refusal the limiter ignored would answer 429 for
            // existing names and 401 for the rest, which is the enumeration oracle again.
            attemptLimiter.recordFailure(attemptKey)
            throw if (user == null) {
                UserAuthenticationUserDoesNotExistError()
            } else {
                UserAuthenticationInvalidPasswordError()
            }
        }
        if (!passwordHasher.matches(login.password, hash)) {
            attemptLimiter.recordFailure(attemptKey)
            throw UserAuthenticationInvalidPasswordError()
        }
        attemptLimiter.recordSuccess(attemptKey)
        return user
    }
}
