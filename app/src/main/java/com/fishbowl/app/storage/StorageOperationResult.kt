package com.fishbowl.app.storage

/**
 * Result of a filesystem or storage operation (backup, restore, atomic move).
 */
sealed class StorageOperationResult {
    data class Success(
        val path: String,
        val bytesTransferred: Long,
        val checksumSha256: String? = null
    ) : StorageOperationResult()

    data class Failure(
        val path: String,
        val error: String,
        val exception: Throwable? = null
    ) : StorageOperationResult()
}
