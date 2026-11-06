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
     * Checks if an HTTP endpoint responds with a recognized readiness status code.
     * Keeps API keys out of query URLs by using headers where applicable.
     */
    @JvmOverloads
    fun checkEndpoint(urlStr: String, apiKey: String? = null, timeoutMs: Int = defaultTimeoutMs): Boolean {
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
            val code = conn.responseCode
            (code in 200..399) || code == 401 || code == 409
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Performs a comprehensive multi-tier assessment of a service's health.
     */
    @JvmOverloads
    fun assessHealth(
        serviceId: String,
        port: Int,
        healthEndpointUrl: String? = null,
        apiKey: String? = null
    ): ServiceHealthSnapshot {
        val startTime = System.currentTimeMillis()
        val portListening = isPortListening(port)
        if (!portListening) {
            return ServiceHealthSnapshot(
                serviceId = serviceId,
                state = ServiceHealthState.STOPPED,
                isPortListening = false,
                isApiResponding = false,
                latencyMs = -1L
            )
        }

        if (healthEndpointUrl.isNullOrBlank()) {
            return ServiceHealthSnapshot(
                serviceId = serviceId,
                state = ServiceHealthState.PORT_LISTENING,
                isPortListening = true,
                isApiResponding = false,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        val apiResponding = checkEndpoint(healthEndpointUrl, apiKey)
        val latency = System.currentTimeMillis() - startTime
        val state = if (apiResponding) {
            ServiceHealthState.READY
        } else {
            ServiceHealthState.PORT_LISTENING
        }

        return ServiceHealthSnapshot(
            serviceId = serviceId,
            state = state,
            isPortListening = true,
            isApiResponding = apiResponding,
            latencyMs = latency
        )
    }
}
