package com.fishbowl.app.services

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Dedicated health monitor assessing process status, port occupancy,
 * HTTP responsiveness, and API readiness across media services.
 */
class ServiceHealthMonitor @JvmOverloads constructor(
    private val defaultTimeoutMs: Int = 2500
) {

    /**
     * Checks if a local TCP port is accepting connections.
     */
    @JvmOverloads
    fun isPortListening(port: Int, timeoutMs: Int = 350): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Determines whether an HTTP status code indicates READY for a given service.
     *
     * - Transmission: Returns 409 Conflict as a standard CSRF token challenge on its RPC/web endpoint,
     *   which confirms the daemon is active, listening, and responsive. 200..399 are also considered READY.
     * - Other services (Jellyfin, Radarr, Sonarr, Prowlarr, Seerr, Bazarr, Lidarr, Jackett):
     *   Require HTTP 200..399 to be considered READY.
     *   HTTP 401 (Unauthorized) indicates the API is responding but requires authentication; it is NOT READY.
     *   HTTP 409 on non-Transmission services indicates a conflict error; it is NOT READY.
     */
    fun isReadyStatusCode(serviceId: String?, statusCode: Int): Boolean {
        if (statusCode in 200..399) {
            return true
        }
        if ("transmission".equals(serviceId, ignoreCase = true) && statusCode == 409) {
            return true
        }
        return false
    }

    /**
     * Queries an HTTP endpoint and returns its status code, or -1 on network failure.
     */
    @JvmOverloads
    fun getHttpStatusCode(
        urlStr: String,
        apiKey: String? = null,
        timeoutMs: Int = defaultTimeoutMs
    ): Int {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            if (!apiKey.isNullOrBlank()) {
                conn.setRequestProperty("X-Api-Key", apiKey.trim())
            }
            conn.responseCode
        } catch (_: Exception) {
            -1
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Checks if an HTTP endpoint responds with a recognized readiness status code for a specific service.
     */
    @JvmOverloads
    fun checkServiceEndpoint(
        serviceId: String,
        urlStr: String,
        apiKey: String? = null,
        timeoutMs: Int = defaultTimeoutMs
    ): Boolean {
        val code = getHttpStatusCode(urlStr, apiKey, timeoutMs)
        return isReadyStatusCode(serviceId, code)
    }

    /**
     * General endpoint check. Strictly requires 200..399.
     */
    @JvmOverloads
    fun checkEndpoint(
        urlStr: String,
        apiKey: String? = null,
        timeoutMs: Int = defaultTimeoutMs
    ): Boolean {
        val code = getHttpStatusCode(urlStr, apiKey, timeoutMs)
        return code in 200..399
    }

    /**
     * Performs a comprehensive multi-tier assessment of a service's health:
     * - STOPPED: Port not listening, process not alive
     * - PROCESS_RUNNING: Process alive, port not yet listening
     * - PORT_LISTENING: Port listening, HTTP endpoint not yet responding or timed out
     * - API_RESPONDING: HTTP endpoint responding (e.g. 401 Unauthorized or initializing) but not READY
     * - READY: Confirmed ready based on service readiness specification (200..399, or 409 for Transmission)
     * - DEGRADED: HTTP endpoint returning 5xx server error
     */
    @JvmOverloads
    fun assessHealth(
        serviceId: String,
        port: Int,
        healthEndpointUrl: String? = null,
        apiKey: String? = null,
        isProcessAlive: Boolean = false
    ): ServiceHealthSnapshot {
        val startTime = System.currentTimeMillis()
        val portListening = isPortListening(port)

        if (!portListening) {
            val state = if (isProcessAlive) {
                ServiceHealthState.PROCESS_RUNNING
            } else {
                ServiceHealthState.STOPPED
            }
            return ServiceHealthSnapshot(
                serviceId = serviceId,
                state = state,
                isPortListening = false,
                isApiResponding = false,
                latencyMs = -1L,
                httpStatusCode = -1
            )
        }

        // Port is listening
        if (healthEndpointUrl.isNullOrBlank()) {
            return ServiceHealthSnapshot(
                serviceId = serviceId,
                state = ServiceHealthState.PORT_LISTENING,
                isPortListening = true,
                isApiResponding = false,
                latencyMs = System.currentTimeMillis() - startTime,
                httpStatusCode = -1
            )
        }

        val statusCode = getHttpStatusCode(healthEndpointUrl, apiKey, defaultTimeoutMs)
        val latency = System.currentTimeMillis() - startTime

        val (state, apiResponding) = when {
            isReadyStatusCode(serviceId, statusCode) -> {
                ServiceHealthState.READY to true
            }
            statusCode in 500..599 -> {
                ServiceHealthState.DEGRADED to true
            }
            statusCode == 401 || statusCode in 400..499 -> {
                ServiceHealthState.API_RESPONDING to true
            }
            statusCode > 0 -> {
                ServiceHealthState.API_RESPONDING to true
            }
            else -> {
                ServiceHealthState.PORT_LISTENING to false
            }
        }

        return ServiceHealthSnapshot(
            serviceId = serviceId,
            state = state,
            isPortListening = true,
            isApiResponding = apiResponding,
            latencyMs = latency,
            httpStatusCode = statusCode
        )
    }
}
