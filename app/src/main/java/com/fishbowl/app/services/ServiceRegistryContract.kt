package com.fishbowl.app.services

import com.fishbowl.app.model.ManagedService

/**
 * Contract governing service discovery, registration and status querying.
 */
interface ServiceRegistryContract {
    fun getService(id: String): ManagedService?
    fun getAllServices(): List<ManagedService>
    fun getRunningServicesCount(): Int
}
