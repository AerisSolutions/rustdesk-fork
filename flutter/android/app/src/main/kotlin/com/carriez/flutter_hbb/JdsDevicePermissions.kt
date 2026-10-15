package com.carriez.flutter_hbb

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import java.lang.reflect.InvocationTargetException

// Apply RustDesk permissions automatically so the user does not have to
@Suppress("DEPRECATION")
internal fun MainActivity.applyDevicePermissions() {
    val tag = "RD_SETUP"

    if (Build.VERSION.SDK_INT < 23) {
        Log.e(tag, "This setup implementation requires Android 6 or newer")
        return
    }

    var passed = 0
    var failed = 0

    // Each operation runs independently so one failure doesn't stop the rest.
    fun step(name: String, action: () -> Unit) {
        try {
            action()
            passed++
            Log.i(tag, "OK: $name")
        } catch (e: Exception) {
            val cause = if (e is InvocationTargetException) e.targetException ?: e else e
            failed++
            Log.e(tag, "FAILED: $name: ${cause.message}", cause)
        }
    }

    fun requirePermission(permission: String) {
        check(checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "$permission not granted. Check manifest and platform signing key."
        }
    }

    fun grantRuntimePermission(permission: String) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        val requested = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions

        check(requested?.contains(permission) == true) {
            "$permission is not declared for this Android version"
        }

        requirePermission("android.permission.GRANT_RUNTIME_PERMISSIONS")

        val method = PackageManager::class.java.getMethod("grantRuntimePermission", String::class.java, String::class.java, UserHandle::class.java)
        method.invoke(packageManager, packageName, permission, Process.myUserHandle())

        check(checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "$permission was still denied after grant"
        }
    }

    fun allowAppOp(operation: String) {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        fun currentMode(): Int {
            return if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(operation, Process.myUid(), packageName)
            } else {
                appOps.checkOpNoThrow(operation, Process.myUid(), packageName)
            }
        }

        if (currentMode() == AppOpsManager.MODE_ALLOWED) {
            return
        }

        requirePermission("android.permission.MANAGE_APP_OPS_MODES")

        val method = AppOpsManager::class.java.getMethod("setMode", String::class.java, Int::class.javaPrimitiveType!!, String::class.java, Int::class.javaPrimitiveType!!)
        method.invoke(appOps, operation, Process.myUid(), packageName, AppOpsManager.MODE_ALLOWED)

        check(currentMode() == AppOpsManager.MODE_ALLOWED) {
            "$operation was still not allowed after setMode"
        }
    }

    Log.i(tag, "Starting setup: SDK=${Build.VERSION.SDK_INT}, targetSDK=${applicationInfo.targetSdkVersion}")

    // 1. Runtime permissions
    step("Microphone permission") {
        grantRuntimePermission("android.permission.RECORD_AUDIO")
    }

    if (Build.VERSION.SDK_INT >= 33) {
        step("Notification permission") {
            grantRuntimePermission("android.permission.POST_NOTIFICATIONS")
        }
    }

    // Legacy storage permissions only where they remain applicable.
    if (Build.VERSION.SDK_INT < 33 || applicationInfo.targetSdkVersion < 33) {
        step("Legacy read-storage permission") {
            grantRuntimePermission("android.permission.READ_EXTERNAL_STORAGE")
        }
    }

    if (Build.VERSION.SDK_INT < 30 || applicationInfo.targetSdkVersion < 30) {
        step("Legacy write-storage permission") {
            grantRuntimePermission("android.permission.WRITE_EXTERNAL_STORAGE")
        }
    }

    // 2. Special access / AppOps
    if (Build.VERSION.SDK_INT >= 33) {
        step("Allow restricted settings") {
            allowAppOp("android:access_restricted_settings")
        }
    }

    if (Build.VERSION.SDK_INT >= 30) {
        step("Manage all files") {
            allowAppOp("android:manage_external_storage")

            check(android.os.Environment.isExternalStorageManager()) {
                "Android still reports all-files access disabled"
            }
        }
    }

    step("Display over other apps") {
        allowAppOp("android:system_alert_window")

        check(Settings.canDrawOverlays(this)) {
            "Android still reports overlay access disabled"
        }
    }

    step("Screen-capture AppOp") {
        allowAppOp("android:project_media")
    }

    Log.i(tag, "CAPTURE_VIDEO_OUTPUT granted=${checkSelfPermission("android.permission.CAPTURE_VIDEO_OUTPUT") == PackageManager.PERMISSION_GRANTED}")

    // 3. Enable accessibility without removing other enabled services.
    step("Accessibility service configured") {
        val service = ComponentName(this, InputService::class.java)
        val resolver = contentResolver
        val existing = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter { it.isNotBlank() }
        val alreadyListed = existing.any { ComponentName.unflattenFromString(it) == service }
        val accessibilityEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

        if (!alreadyListed || !accessibilityEnabled) {
            requirePermission("android.permission.WRITE_SECURE_SETTINGS")

            if (!alreadyListed) {
                val updated = (existing + service.flattenToString()).joinToString(":")

                check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)) {
                    "Failed to update enabled accessibility services"
                }
            }

            if (!accessibilityEnabled) {
                check(Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) {
                    "Failed to enable accessibility"
                }
            }
        }

        val savedServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

        check(savedServices.split(':').any { ComponentName.unflattenFromString(it) == service }) {
            "RustDesk accessibility service was not saved"
        }

        check(Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) {
            "Accessibility remains disabled"
        }

        // Binding is asynchronous. Saved settings don't prove input works.
        Log.i(tag, "Accessibility binding may take a moment")
    }

    // 4. Android battery optimisation exemption
    step("Battery-optimisation exemption") {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            requirePermission("android.permission.DEVICE_POWER")

            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "deviceidle") as? IBinder ?: error("Android deviceidle service is unavailable")
            val stub = Class.forName("android.os.IDeviceIdleController\$Stub")
            val controller = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: error("Could not access deviceidle controller")

            Class.forName("android.os.IDeviceIdleController").getMethod("addPowerSaveWhitelistApp", String::class.java).invoke(controller, packageName)

            check(powerManager.isIgnoringBatteryOptimizations(packageName)) {
                "Android still reports battery optimisation enabled"
            }
        }
    }

    Log.i(tag, "Setup finished: $passed successful, $failed failed")
}
