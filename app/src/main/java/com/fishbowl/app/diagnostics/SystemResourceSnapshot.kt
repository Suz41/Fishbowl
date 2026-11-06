package com.fishbowl.app.diagnostics

/**
 * Lightweight snapshot of system resource utilization focusing on storage and thermal diagnostics.
 * Conforms to lean monitoring guidelines: no CPU monitoring, no RAM monitoring.
 */
data class SystemResourceSnapshot @JvmOverloads constructor(
    val internalStorageFreeBytes: Long,
    val internalStorageTotalBytes: Long,
    val activeServicesCount: Int,
    val thermalStatus: Int = -1,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isStorageLow: Boolean
        get() = internalStorageFreeBytes < (200L * 1024L * 1024L)

    val storageFreeMb: Long
        get() = internalStorageFreeBytes / (1024L * 1024L)

    val storageTotalMb: Long
        get() = internalStorageTotalBytes / (1024L * 1024L)
}
