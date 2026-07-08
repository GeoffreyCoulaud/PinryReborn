package fr.geoffreyCoulaud.pinryReborn.api.usecases.tasks.exceptions

class PermanentTaskException(val reason: String) : RuntimeException(reason)
