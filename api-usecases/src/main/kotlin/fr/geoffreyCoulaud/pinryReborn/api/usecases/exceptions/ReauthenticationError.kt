package fr.geoffreyCoulaud.pinryReborn.api.usecases.exceptions

class ReauthenticationError :
    BaseError(message = "Re-authentication failed", code = ErrorCode.REAUTHENTICATION_FAILED)
