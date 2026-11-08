package com.fishbowl.app.core

import com.fishbowl.app.NetworkUtils

/**
 * Verification bridge demonstrating bidirectional Java and Kotlin interoperability.
 */
class KotlinInteropBridge {
    companion object {
        @JvmStatic
        fun getKotlinRuntimeVersion(): String = "2.1.20"

        @JvmStatic
        fun calculateDigest(input: String): String = "interop:" + input.length
    }

    fun echo(message: String): String {
        return "Echo: $message"
    }

    fun callJavaUtil(value: String): Boolean {
        return NetworkUtils.isTailscaleIp(value)
    }
}
