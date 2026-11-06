package com.fishbowl.app.ui

import android.graphics.Color
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

    @JvmStatic
    fun getServiceBrandColor(serviceId: String?): Int {
        if (serviceId == null) return Color.WHITE
        if ("SRR" == serviceId) return Color.parseColor("#CCFBFA")

        return when (serviceId.lowercase(Locale.ROOT)) {
            "radarr", "rrr" -> Color.parseColor("#FF9E20")
            "sonarr", "snr" -> Color.parseColor("#CCFBFA")
            "prowlarr", "prr" -> Color.parseColor("#FFFC8C")
            "jackett", "jkt", "jk" -> Color.parseColor("#F97316")
            "transmission", "trr" -> Color.parseColor("#FF788D")
            "seerr", "srr", "se", "see" -> Color.parseColor("#55A9A0")
            "bazarr", "brr", "bzr" -> Color.parseColor("#5D9253")
            "lidarr", "lrr", "ldr" -> Color.parseColor("#769826")
            "flaresolverr", "flr" -> Color.parseColor("#34D399")
            "jellyfin", "jfn", "jf" -> Color.parseColor("#AB03A9")
            else -> Color.WHITE
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
            return ServiceStatusPresentation("UNKNOWN", Color.parseColor("#71717A"), Color.WHITE, isRunning = false, isStarting = false)
        }

        val id = service.id.lowercase(Locale.ROOT)
        if ("jellyfin" == id) {
            return when {
                isJellyfinRunning -> ServiceStatusPresentation(
                    "RUNNING",
                    Color.parseColor("#34D399"),
                    getServiceBrandColor("jellyfin"),
                    isRunning = true,
                    isStarting = false
                )
                isJellyfinStarting -> ServiceStatusPresentation(
                    "STARTING",
                    Color.parseColor("#F59E0B"),
                    Color.parseColor("#F59E0B"),
                    isRunning = false,
                    isStarting = true
                )
                else -> ServiceStatusPresentation(
                    "OFF",
                    Color.parseColor("#71717A"),
                    Color.WHITE,
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
                Color.parseColor("#34D399"),
                brandColor,
                isRunning = true,
                isStarting = false
            )
            ServiceState.STARTING, ServiceState.SOCKET_READY, ServiceState.PROCESS_READY -> ServiceStatusPresentation(
                "STARTING",
                Color.parseColor("#F59E0B"),
                Color.parseColor("#F59E0B"),
                isRunning = false,
                isStarting = true
            )
            ServiceState.STOPPING -> ServiceStatusPresentation(
                "STOPPING",
                Color.parseColor("#F59E0B"),
                Color.parseColor("#F59E0B"),
                isRunning = false,
                isStarting = false
            )
            ServiceState.RECOVERING -> ServiceStatusPresentation(
                "RECOVERING",
                Color.parseColor("#F59E0B"),
                Color.parseColor("#F59E0B"),
                isRunning = false,
                isStarting = true
            )
            ServiceState.ERROR, ServiceState.CRASH_LOOP_PROTECTION -> ServiceStatusPresentation(
                "FAILED",
                Color.parseColor("#EF4444"),
                Color.parseColor("#EF4444"),
                isRunning = false,
                isStarting = false
            )
            ServiceState.NOT_INSTALLED -> ServiceStatusPresentation(
                "NOT INSTALLED",
                Color.parseColor("#71717A"),
                Color.parseColor("#71717A"),
                isRunning = false,
                isStarting = false
            )
            ServiceState.DISABLED -> ServiceStatusPresentation(
                "DISABLED",
                Color.parseColor("#71717A"),
                Color.parseColor("#71717A"),
                isRunning = false,
                isStarting = false
            )
            ServiceState.BLOCKED -> ServiceStatusPresentation(
                "BLOCKED",
                Color.parseColor("#EF4444"),
                Color.parseColor("#EF4444"),
                isRunning = false,
                isStarting = false
            )
            else -> ServiceStatusPresentation(
                "OFF",
                Color.parseColor("#71717A"),
                Color.WHITE,
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
