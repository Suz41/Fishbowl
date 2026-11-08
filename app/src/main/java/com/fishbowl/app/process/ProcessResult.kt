package com.fishbowl.app.process

/**
 * Process execution result capturing status code and diagnostic output.
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long
) {
    val isSuccessful: Boolean get() = exitCode == 0
}
