package com.fishbowl.app.services

/**
 * Granular health state for a managed media service.
 */
enum class ServiceHealthState {
    STOPPED,
    STARTING,
    PROCESS_RUNNING,
    PORT_LISTENING,
    API_RESPONDING,
    READY,
    DEGRADED,
    FAILED
}

/**
 * Immutable snapshot of a service health assessment.
 */
data class ServiceHealthSnapshot @JvmOverloads constructor(
    val serviceId: String,
    val state: ServiceHealthState,
    val isPortListening: Boolean,
    val isApiResponding: Boolean,
    val latencyMs: Long = -1L,
    val httpStatusCode: Int = -1,
    val lastError: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    constructor(
        serviceId: String,
        state: ServiceHealthState,
        isPortListening: Boolean,
        isApiResponding: Boolean,
        latencyMs: Long,
        lastError: String?,
        timestampMs: Long
    ) : this(
        serviceId = serviceId,
        state = state,
        isPortListening = isPortListening,
        isApiResponding = isApiResponding,
        latencyMs = latencyMs,
        httpStatusCode = -1,
        lastError = lastError,
        timestampMs = timestampMs
    )
}
