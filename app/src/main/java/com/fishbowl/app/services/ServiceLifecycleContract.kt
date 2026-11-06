package com.fishbowl.app.services

/**
 * Functional callback receiving the outcome of a service lifecycle command.
 */
fun interface ServiceLifecycleCallback {
    fun onResult(result: ServiceLifecycleResult)
}

/**
 * Contract governing individual service lifecycle transitions (start, stop, restart).
 */
interface ServiceLifecycleContract {
    fun startService(id: String)
    fun startService(id: String, callback: ServiceLifecycleCallback?)
    fun stopService(id: String)
    fun stopService(id: String, callback: ServiceLifecycleCallback?)
    fun restartService(id: String)
    fun restartService(id: String, callback: ServiceLifecycleCallback?)
    fun isServiceRunning(id: String): Boolean
}
