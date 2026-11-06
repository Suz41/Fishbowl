package com.fishbowl.app.ui

import com.fishbowl.app.model.ManagedService
import com.fishbowl.app.model.ServiceState
import java.util.Locale

/**
 * UI presentation model capturing visual badges and colors for service status displays.
 */
data class ServiceStatusPresentation(
    val statusText: String,
    val statusColor: Int,
    val iconTint: Int,
    val isRunning: Boolean,
    val isStarting: Boolean
)

/**
 * Extracted dashboard presentation helper providing clean styling, brand colors,
 * and status rendering without coupling to the Android Activity lifecycle.
 */
object DashboardPresenter {

    private fun parseHexColor(colorStr: String): Int {
        return try {
            if (colorStr.startsWith("#")) {
                val hex = colorStr.substring(1)
                val color = hex.toLong(16)
                if (hex.length == 6) {
                    (color or 0xFF000000L).toInt()
                } else {
                    color.toInt()
                }
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    @JvmStatic
    fun getServiceBrandColor(serviceId: String?): Int {
        if (serviceId == null) return -1
        if ("SRR" == serviceId) return parseHexColor("#CCFBFA")

        return when (serviceId.lowercase(Locale.ROOT)) {
            "radarr", "rrr" -> parseHexColor("#FF9E20")
            "sonarr", "snr" -> parseHexColor("#CCFBFA")
            "prowlarr", "prr" -> parseHexColor("#FFFC8C")
            "jackett", "jkt", "jk" -> parseHexColor("#F97316")
            "transmission", "trr" -> parseHexColor("#FF788D")
            "seerr", "srr", "se", "see" -> parseHexColor("#55A9A0")
            "bazarr", "brr", "bzr" -> parseHexColor("#5D9253")
            "lidarr", "lrr", "ldr" -> parseHexColor("#769826")
            "flaresolverr", "flr" -> parseHexColor("#34D399")
            "jellyfin", "jfn", "jf" -> parseHexColor("#AB03A9")
            else -> -1
        }
    }

    @JvmStatic
    @JvmOverloads
    fun resolveStatusPresentation(
        service: ManagedService?,
        isJellyfinRunning: Boolean = false,
        isJellyfinStarting: Boolean = false
    ): ServiceStatusPresentation {
        if (service == null) {
            return ServiceStatusPresentation("UNKNOWN", parseHexColor("#71717A"), -1, isRunning = false, isStarting = false)
        }

        val id = service.id.lowercase(Locale.ROOT)
        if ("jellyfin" == id) {
            return when {
                isJellyfinRunning -> ServiceStatusPresentation(
                    "RUNNING",
                    parseHexColor("#34D399"),
                    getServiceBrandColor("jellyfin"),
                    isRunning = true,
                    isStarting = false
                )
                isJellyfinStarting -> ServiceStatusPresentation(
                    "STARTING",
                    parseHexColor("#F59E0B"),
                    parseHexColor("#F59E0B"),
                    isRunning = false,
                    isStarting = true
                )
                else -> ServiceStatusPresentation(
                    "OFF",
                    parseHexColor("#71717A"),
                    -1,
                    isRunning = false,
                    isStarting = false
                )
            }
        }

        val state = service.state
        val brandColor = getServiceBrandColor(id)

        return when (state) {
            ServiceState.RUNNING -> ServiceStatusPresentation(
                "RUNNING",
                parseHexColor("#34D399"),
                brandColor,
                isRunning = true,
                isStarting = false
            )
            ServiceState.STARTING, ServiceState.SOCKET_READY, ServiceState.PROCESS_READY -> ServiceStatusPresentation(
                "STARTING",
                parseHexColor("#F59E0B"),
                parseHexColor("#F59E0B"),
                isRunning = false,
                isStarting = true
            )
            ServiceState.STOPPING -> ServiceStatusPresentation(
                "STOPPING",
                parseHexColor("#F59E0B"),
                parseHexColor("#F59E0B"),
                isRunning = false,
                isStarting = false
            )
            ServiceState.RECOVERING -> ServiceStatusPresentation(
                "RECOVERING",
                parseHexColor("#F59E0B"),
                parseHexColor("#F59E0B"),
                isRunning = false,
                isStarting = true
            )
            ServiceState.ERROR, ServiceState.CRASH_LOOP_PROTECTION -> ServiceStatusPresentation(
                "FAILED",
                parseHexColor("#EF4444"),
                parseHexColor("#EF4444"),
                isRunning = false,
                isStarting = false
            )
            ServiceState.NOT_INSTALLED -> ServiceStatusPresentation(
                "NOT INSTALLED",
                parseHexColor("#71717A"),
                parseHexColor("#71717A"),
                isRunning = false,
                isStarting = false
            )
            ServiceState.DISABLED -> ServiceStatusPresentation(
                "DISABLED",
                parseHexColor("#71717A"),
                parseHexColor("#71717A"),
                isRunning = false,
                isStarting = false
            )
            ServiceState.BLOCKED -> ServiceStatusPresentation(
                "BLOCKED",
                parseHexColor("#EF4444"),
                parseHexColor("#EF4444"),
                isRunning = false,
                isStarting = false
            )
            else -> ServiceStatusPresentation(
                "OFF",
                parseHexColor("#71717A"),
                -1,
                isRunning = false,
                isStarting = false
            )
        }
    }

    @JvmStatic
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var b = bytes.toDouble()
        var idx = 0
        while (b >= 1024.0 && idx < units.size - 1) {
            b /= 1024.0
            idx++
        }
        return String.format(Locale.ROOT, "%.1f %s", b, units[idx])
    }
}
