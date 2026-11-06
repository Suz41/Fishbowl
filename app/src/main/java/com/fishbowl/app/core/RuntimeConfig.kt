package com.fishbowl.app.core

/**
 * Immutable application-wide runtime and deployment configuration.
 */
data class RuntimeConfig(
    val appVersionName: String = "1.4.10-beta.2",
    val appVersionCode: Int = 101418,
    val isArm64Supported: Boolean = true,
    val defaultJellyfinPort: Int = 8096,
    val maxConcurrentServiceStarts: Int = 3,
    val healthCheckTimeoutMs: Int = 3000
)
