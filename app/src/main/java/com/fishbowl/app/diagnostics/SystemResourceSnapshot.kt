package com.fishbowl.app.diagnostics

/**
 * Lightweight snapshot of system resource utilization.
 */
data class SystemResourceSnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val appUsedRamBytes: Long,
    val internalStorageFreeBytes: Long,
    val internalStorageTotalBytes: Long,
    val isLowMemory: Boolean,
    val activeServicesCount: Int,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val ramUsagePercent: Float
        get() = if (totalRamBytes > 0) {
            ((totalRamBytes - availableRamBytes).toFloat() / totalRamBytes.toFloat()) * 100f
        } else 0f
}
