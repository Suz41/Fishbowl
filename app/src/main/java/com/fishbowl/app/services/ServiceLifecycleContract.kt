package com.fishbowl.app.services

/**
 * Contract governing individual service lifecycle transitions (start, stop, restart).
 */
interface ServiceLifecycleContract {
    fun startService(id: String)
    fun stopService(id: String)
    fun restartService(id: String)
    fun isServiceRunning(id: String): Boolean
}
