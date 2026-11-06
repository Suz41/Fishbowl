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
data class ServiceHealthSnapshot(
    val serviceId: String,
    val state: ServiceHealthState,
    val isPortListening: Boolean,
    val isApiResponding: Boolean,
    val latencyMs: Long = -1L,
    val lastError: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)
