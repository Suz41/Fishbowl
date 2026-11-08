package com.fishbowl.app.security

import android.content.Context
import com.fishbowl.app.JellyfinAccountManager
import com.fishbowl.app.JellyfinController
import com.fishbowl.app.services.MediaServiceManager
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Kotlin security manager providing logical Jellyfin user credential access,
 * service identity mapping ("which ID is what"), and zombie process port conflict resolution.
 */
object FishbowlSecurityManager {

    data class JellyfinUserRecord(
        val username: String,
        val hasPassword: Boolean,
        val storedHash: String?,
        val knownPlaintextPassword: String?,
        val isAdmin: Boolean,
        val lastLoginDate: String?,
        val lastActivityDate: String?,
        val invalidLoginAttempts: Int
    )

    data class ServiceSecurityProfile(
        val serviceId: String,
        val displayName: String,
        val defaultPort: Int,
        val defaultPath: String,
        val authMode: String,
        val roleDescription: String
    )

    data class ZombiePortStatus(
        val port: Int,
        val serviceId: String,
        val serviceName: String,
        val isOccupied: Boolean,
        val isManagedOnline: Boolean,
        val isZombieConflict: Boolean
    )

    private val SERVICE_PROFILES = listOf(
        ServiceSecurityProfile(
            serviceId = "jellyfin",
            displayName = "Jellyfin Media Server",
            defaultPort = 8096,
            defaultPath = "/web/",
            authMode = "Direct Database Auth (Salted Hash)",
            roleDescription = "Core media streaming engine and library catalog"
        ),
        ServiceSecurityProfile(
            serviceId = "radarr",
            displayName = "Radarr Movie Automator",
            defaultPort = 7878,
            defaultPath = "/",
            authMode = "API Key & Web Forms Auth",
            roleDescription = "Automated movie searching, downloading, and sorting"
        ),
        ServiceSecurityProfile(
            serviceId = "sonarr",
            displayName = "Sonarr TV Series Automator",
            defaultPort = 8989,
            defaultPath = "/",
            authMode = "API Key & Web Forms Auth",
            roleDescription = "Automated TV show tracking, season management, and renaming"
        ),
        ServiceSecurityProfile(
            serviceId = "prowlarr",
            displayName = "Prowlarr Indexer Proxy",
            defaultPort = 9696,
            defaultPath = "/",
            authMode = "API Key & HTTP Basic Auth",
            roleDescription = "Centralized torrent & Usenet indexer synchronization"
        ),
        ServiceSecurityProfile(
            serviceId = "transmission",
            displayName = "Transmission BitTorrent Engine",
            defaultPort = 9091,
            defaultPath = "/transmission/web/",
            authMode = "RPC Authentication (fishbowl / fishbowl)",
            roleDescription = "High-performance BitTorrent download client with RPC control"
        ),
        ServiceSecurityProfile(
            serviceId = "seerr",
            displayName = "Jellyseerr Media Discovery",
            defaultPort = 5055,
            defaultPath = "/",
            authMode = "Jellyfin SSO / Local Admin",
            roleDescription = "Interactive media request, discovery, and recommendation portal"
        ),
        ServiceSecurityProfile(
            serviceId = "bazarr",
            displayName = "Bazarr Subtitle Companion",
            defaultPort = 6767,
            defaultPath = "/",
            authMode = "API Key & Web Forms Auth",
            roleDescription = "Automated subtitle synchronization for Radarr and Sonarr"
        ),
        ServiceSecurityProfile(
            serviceId = "jackett",
            displayName = "Jackett Torrent Scraper",
            defaultPort = 9117,
            defaultPath = "/",
            authMode = "Admin API Key",
            roleDescription = "Legacy torrent tracker scraper and indexer bridge"
        ),
        ServiceSecurityProfile(
            serviceId = "lidarr",
            displayName = "Lidarr Music Automator",
            defaultPort = 8686,
            defaultPath = "/",
            authMode = "API Key & Web Forms Auth",
            roleDescription = "Music album tracking, downloading, and library organization"
        )
    )

    /**
     * Queries registered Jellyfin accounts from SQLite database.
     */
    @JvmStatic
    fun getRegisteredUsers(context: Context): List<JellyfinUserRecord> {
        val rawUsers = JellyfinAccountManager.getRegisteredUsers(context)
        return rawUsers.map { u ->
            JellyfinUserRecord(
                username = u.username,
                hasPassword = u.hasPassword,
                storedHash = u.passwordHash,
                knownPlaintextPassword = u.knownPlaintextPassword,
                isAdmin = u.isAdmin,
                lastLoginDate = u.lastLoginDate,
                lastActivityDate = u.lastActivityDate,
                invalidLoginAttempts = u.invalidLoginAttempts
            )
        }
    }

    /**
     * Resets a Jellyfin account's password to blank directly in the database.
     */
    @JvmStatic
    fun resetPasswordToBlank(context: Context, username: String): Boolean {
        return JellyfinAccountManager.resetPasswordToBlank(context, username)
    }

    /**
     * Sets a user's password directly in the database using standard PBKDF2-SHA512
     * and caches known password for in-app reveal.
     */
    @JvmStatic
    fun setPassword(context: Context, username: String, newPassword: String): Boolean {
        return JellyfinAccountManager.setPassword(context, username, newPassword)
    }

    /**
     * Retrieves any locally known/cached plaintext password for a Jellyfin user.
     */
    @JvmStatic
    fun getKnownPassword(context: Context, username: String): String? {
        return JellyfinAccountManager.getKnownPassword(context, username)
    }

    @JvmStatic
    fun storeKnownPassword(context: Context, username: String, plainPassword: String) {
        JellyfinAccountManager.storeKnownPassword(context, username, plainPassword)
    }

    @JvmStatic
    fun verifyPasswordHash(plainPassword: String, storedHash: String): Boolean {
        return JellyfinAccountManager.verifyPasswordHash(plainPassword, storedHash)
    }

    /**
     * Returns the full list of service security profiles explaining which ID is what.
     */
    @JvmStatic
    fun getServiceProfiles(): List<ServiceSecurityProfile> = SERVICE_PROFILES

    /**
     * Probes all 9 service ports to identify whether any port is occupied by a zombie process.
     */
    @JvmStatic
    fun auditPorts(context: Context): List<ZombiePortStatus> {
        val sm = MediaServiceManager.getInstance(context)
        val jc = JellyfinController.getInstance()

        return SERVICE_PROFILES.map { profile ->
            val isManagedOnline = when (profile.serviceId) {
                "jellyfin" -> jc != null && jc.isRunning
                else -> {
                    val ms = sm?.getService(profile.serviceId)
                    ms != null && ms.isRunning
                }
            }

            var canBind = false
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress("127.0.0.1", profile.defaultPort))
                    canBind = true
                }
            } catch (_: Throwable) {
                canBind = false
            }

            val isOccupied = !canBind
            val isZombieConflict = isOccupied && !isManagedOnline

            ZombiePortStatus(
                port = profile.defaultPort,
                serviceId = profile.serviceId,
                serviceName = profile.displayName,
                isOccupied = isOccupied,
                isManagedOnline = isManagedOnline,
                isZombieConflict = isZombieConflict
            )
        }
    }

    /**
     * Terminates any zombie background processes locking ports.
     */
    @JvmStatic
    fun reclaimAllZombiePorts(context: Context): Int {
        val sm = MediaServiceManager.getInstance(context)
        return sm?.reclaimAllZombiePorts() ?: 0
    }
}
