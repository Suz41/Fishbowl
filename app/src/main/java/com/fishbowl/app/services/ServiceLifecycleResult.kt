package com.fishbowl.app.services

/**
 * Result of a service lifecycle command (start, stop, restart, reload).
 */
sealed class ServiceLifecycleResult {
    abstract val serviceId: String

    data class Success(
        override val serviceId: String,
        val message: String
    ) : ServiceLifecycleResult()

    data class Failure(
        override val serviceId: String,
        val reason: String,
        val cause: Throwable? = null
    ) : ServiceLifecycleResult()

    data class Skipped(
        override val serviceId: String,
        val reason: String
    ) : ServiceLifecycleResult()
}
