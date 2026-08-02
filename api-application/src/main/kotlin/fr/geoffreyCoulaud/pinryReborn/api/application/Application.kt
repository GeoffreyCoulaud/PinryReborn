package fr.geoffreyCoulaud.pinryReborn.api.application

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.annotations.QuarkusMain

@QuarkusMain
class Application {
    companion object {
        @JvmStatic
        @Suppress("SpreadOperator") // Quarkus @QuarkusMain bootstrap: forwards JVM args to Quarkus.run once at startup.
        fun main(args: Array<String>) {
            Quarkus.run(*args)
        }
    }
}
