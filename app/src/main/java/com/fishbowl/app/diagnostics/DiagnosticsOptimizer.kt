package com.fishbowl.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Kotlin diagnostics utility for memory audits, storage reclamation,
 * and bounded diagnostic exports.
 */
object DiagnosticsOptimizer {

    data class RamHealthInfo(
        val totalBytes: Long,
        val availBytes: Long,
        val usedBytes: Long,
        val isLowMemory: Boolean
    ) {
        val usedPercent: Int
            get() = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
    }

    data class StorageFootprintInfo(
        val appCacheBytes: Long,
        val appDataBytes: Long,
        val storageFreeBytes: Long,
        val storageTotalBytes: Long
    )

    /**
     * Inspects system RAM health using ActivityManager.MemoryInfo.
     */
    @JvmStatic
    fun getRamHealth(context: Context): RamHealthInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        if (am != null) {
            am.getMemoryInfo(mi)
            val total = mi.totalMem
            val avail = mi.availMem
            val used = total - avail
            return RamHealthInfo(total, avail, used, mi.lowMemory)
        }
        return RamHealthInfo(0L, 0L, 0L, false)
    }

    /**
     * Calculates storage headroom and app private directory footprints.
     */
    @JvmStatic
    fun getStorageFootprint(context: Context): StorageFootprintInfo {
        var freeBytes = 0L
        var totalBytes = 0L
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            freeBytes = stat.availableBytes
            totalBytes = stat.totalBytes
        } catch (_: Throwable) {}

        var cacheBytes = calculateDirectorySize(context.cacheDir)
        cacheBytes += calculateDirectorySize(context.codeCacheDir)
        context.externalCacheDir?.let { cacheBytes += calculateDirectorySize(it) }

        val dataBytes = calculateDirectorySize(context.filesDir)

        return StorageFootprintInfo(
            appCacheBytes = cacheBytes,
            appDataBytes = dataBytes,
            storageFreeBytes = freeBytes,
            storageTotalBytes = totalBytes
        )
    }

    /**
     * Purges temporary cache directories and stale logs to immediately reclaim storage.
     */
    @JvmStatic
    fun purgeTemporaryCache(context: Context): Long {
        var freed = 0L
        freed += deleteChildren(context.cacheDir)
        freed += deleteChildren(context.codeCacheDir)
        context.externalCacheDir?.let { freed += deleteChildren(it) }

        // Clean stale temporary log files older than 24 hours in filesDir
        try {
            val now = System.currentTimeMillis()
            val cutoff = now - (24 * 60 * 60 * 1000L)
            context.filesDir?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".log") && file.lastModified() < cutoff) {
                    val len = file.length()
                    if (file.delete()) {
                        freed += len
                    }
                }
            }
        } catch (_: Throwable) {}

        return freed
    }

    /**
     * Ensures diagnostic export string stays bounded (<= maxBytes) to prevent memory spikes.
     */
    @JvmStatic
    fun buildBoundedDiagnosticsReport(rawReport: String, maxBytes: Int = 25000): String {
        val bytes = rawReport.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return rawReport
        val truncated = String(bytes, 0, maxBytes - 100, Charsets.UTF_8)
        return "$truncated\n\n[... DIAGNOSTIC REPORT TRUNCATED AT 25 KB BOUNDARY ...]"
    }

    private fun deleteChildren(dir: File?): Long {
        if (dir == null || !dir.exists() || !dir.isDirectory) return 0L
        var bytesFreed = 0L
        try {
            dir.listFiles()?.forEach { child ->
                bytesFreed += calculateDirectorySize(child)
                deleteRecursively(child)
            }
        } catch (_: Throwable) {}
        return bytesFreed
    }

    private fun deleteRecursively(fileOrDir: File?): Boolean {
        if (fileOrDir == null || !fileOrDir.exists()) return true
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { deleteRecursively(it) }
        }
        return fileOrDir.delete()
    }

    private fun calculateDirectorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        try {
            dir.listFiles()?.forEach { child ->
                size += if (child.isDirectory) calculateDirectorySize(child) else child.length()
            }
        } catch (_: Throwable) {}
        return size
    }
}
