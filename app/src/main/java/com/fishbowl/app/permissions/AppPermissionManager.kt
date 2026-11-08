package com.fishbowl.app.permissions

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fishbowl.app.MediaStorageManager

/**
 * Kotlin permission manager providing status checks and unified grant sequencing
 * for Setup and Settings screens.
 */
object AppPermissionManager {

    data class PermissionStatus(
        val storageGranted: Boolean,
        val notificationGranted: Boolean,
        val batteryExempted: Boolean,
        val allEssentialGranted: Boolean
    )

    @JvmStatic
    fun getStatus(context: Context): PermissionStatus {
        val s = isStorageGranted(context)
        val n = isNotificationGranted(context)
        val b = isBatteryOptimizationExempted(context)
        return PermissionStatus(
            storageGranted = s,
            notificationGranted = n,
            batteryExempted = b,
            allEssentialGranted = s && n && b
        )
    }

    @JvmStatic
    fun isStorageGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasVideo = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            val hasAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasVideo || hasAudio) return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun isNotificationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @JvmStatic
    fun isBatteryOptimizationExempted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }
        return true
    }

    @JvmStatic
    fun requestStorage(activity: Activity) {
        MediaStorageManager.getInstance(activity).requestStoragePermission(activity)
    }

    @JvmStatic
    fun requestNotification(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        } else {
            val intent = Intent()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            } else {
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.data = Uri.parse("package:" + activity.packageName)
            }
            try {
                activity.startActivity(intent)
            } catch (_: Throwable) {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    @JvmStatic
    fun requestBatteryOptimizationExemption(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:" + activity.packageName)
                }
                activity.startActivity(intent)
            } catch (_: Throwable) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Throwable) {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }

    @JvmStatic
    fun openOemBackgroundSettings(activity: Activity): Boolean {
        val oemIntents = arrayOf(
            // Vivo / iQOO / OriginOS
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingActivity")),
            Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")).putExtra("packagename", activity.packageName),
            // Xiaomi MIUI / HyperOS
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")),
            // Samsung Device Care
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            // Oppo / Realme / OnePlus
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")),
            Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")),
            // Huawei / Honor
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            // Transsion / Infinix / Tecno
            Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.AutoStartActivity")),
            // Android standard
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )

        for (intent in oemIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * Executes the next ungranted permission prompt in sequence.
     * Returns true if a prompt was launched, false if all are already granted.
     */
    @JvmStatic
    fun grantNextUngrantedPermission(activity: Activity): Boolean {
        if (!isStorageGranted(activity)) {
            requestStorage(activity)
            return true
        }
        if (!isNotificationGranted(activity)) {
            requestNotification(activity)
            return true
        }
        if (!isBatteryOptimizationExempted(activity)) {
            requestBatteryOptimizationExemption(activity)
            return true
        }
        return openOemBackgroundSettings(activity)
    }
}
