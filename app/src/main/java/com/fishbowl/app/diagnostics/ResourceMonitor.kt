package com.fishbowl.app.diagnostics

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.fishbowl.app.services.MediaServiceManager

/**
 * Lightweight, non-intrusive diagnostics monitor for storage thresholds and thermal state.
 * CPU monitoring is intentionally omitted and RAM monitoring has been removed to preserve resources.
 */
class ResourceMonitor(private val context: Context) {

    /**
     * Captures a point-in-time diagnostics snapshot of storage and thermal state.
     */
    fun captureSnapshot(): SystemResourceSnapshot {
        var internalStorageFree = 0L
        var internalStorageTotal = 0L
        try {
            val dataDir = context.filesDir ?: Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)
            internalStorageFree = stat.availableBytes
            internalStorageTotal = stat.totalBytes
        } catch (_: Exception) {}

        var activeServices = 0
        try {
            val sm = MediaServiceManager.getInstance(context)
            if (sm != null) {
                activeServices = sm.getRunningServicesCount()
            }
        } catch (_: Exception) {}

        return SystemResourceSnapshot(
            internalStorageFreeBytes = internalStorageFree,
            internalStorageTotalBytes = internalStorageTotal,
            activeServicesCount = activeServices,
            thermalStatus = getThermalStatus(),
            timestampMs = System.currentTimeMillis()
        )
    }

    /**
     * Checks if available storage is below critical threshold (< 200MB).
     */
    @JvmOverloads
    fun isStorageCriticallyLow(thresholdBytes: Long = 200L * 1024L * 1024L): Boolean {
        return try {
            val dataDir = context.filesDir ?: Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)
            stat.availableBytes < thresholdBytes
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns thermal status integer if supported (Android 10+ / API 29+), or -1 if unsupported.
     */
    fun getThermalStatus(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return powerManager?.currentThermalStatus ?: -1
        }
        return -1
    }
}
