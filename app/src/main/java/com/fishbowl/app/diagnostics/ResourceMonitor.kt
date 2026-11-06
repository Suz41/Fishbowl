package com.fishbowl.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.fishbowl.app.services.MediaServiceManager

/**
 * Lightweight, non-intrusive system resource monitor that samples
 * RAM, storage, process count, thermal state, and app memory usage on demand.
 */
class ResourceMonitor(private val context: Context) {

    /**
     * Captures a point-in-time snapshot of system and application resources.
     */
    fun captureSnapshot(): SystemResourceSnapshot {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memoryInfo)

        val totalRam = memoryInfo.totalMem
        val availRam = memoryInfo.availMem
        val isLowMem = memoryInfo.lowMemory

        // App memory usage
        val runtime = Runtime.getRuntime()
        val appUsedRam = runtime.totalMemory() - runtime.freeMemory()

        // Storage metrics
        var internalStorageFree = 0L
        var internalStorageTotal = 0L
        try {
            val dataDir = context.filesDir ?: Environment.getDataDirectory()
            val stat = StatFs(dataDir.path)
            internalStorageFree = stat.availableBytes
            internalStorageTotal = stat.totalBytes
        } catch (_: Exception) {}

        // Active service count
        var activeServices = 0
        try {
            val sm = MediaServiceManager.getInstance(context)
            if (sm != null) {
                activeServices = sm.runningServicesCount
            }
        } catch (_: Exception) {}

        return SystemResourceSnapshot(
            totalRamBytes = totalRam,
            availableRamBytes = availRam,
            appUsedRamBytes = appUsedRam,
            internalStorageFreeBytes = internalStorageFree,
            internalStorageTotalBytes = internalStorageTotal,
            isLowMemory = isLowMem,
            activeServicesCount = activeServices,
            timestampMs = System.currentTimeMillis()
        )
    }

    /**
     * Checks if available storage is below critical threshold (< 200MB).
     */
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
