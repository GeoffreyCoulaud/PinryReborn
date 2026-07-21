package fr.geoffreyCoulaud.pinryReborn.api.presentation.quarkus.security

import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.SessionToken
import fr.geoffreyCoulaud.pinryReborn.api.domain.entities.User
import io.quarkus.security.identity.SecurityIdentity
import java.util.UUID

fun SecurityIdentity.getUser(): User =
    getAttribute("user") as User

fun SecurityIdentity.getUserId(): UUID =
    getAttribute("userId") as UUID

fun SecurityIdentity.getSessionToken(): SessionToken =
    getAttribute("sessionToken") as SessionToken
