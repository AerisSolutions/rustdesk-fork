package com.carriez.flutter_hbb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ffi.JdsFFI

// JDS: the companion app broadcasts provisioning here; applied natively (JdsFFI)
// Agent calls setupRustDeskDevice() -> creates password -> sendBroadcast (.PROVISION)
// Android delivers it to RustDesk's registered receiver (onReceive runs)
// onReceive reads the extras and calls JdsFFI setLocalOption, setOption and setPasswordAndGetId
// each of these jump into jds_ffi.rs which calls RustDesk's own functions
// setPasswordAndGetId returns RustDesk's ID back up to Kotlin
// onReceive sends ID back to the agent as another broadcast PROVISIONED_ID which the agent is listening for
// Declared in jds_AndroidManifest.xml (not registered in MainService), so the PROVISION broadcast
// cold starts RustDesk even when nothing is running; the sender must hold the signature level
// io.jdslab.sheriff.permission.PROVISION, so only an app signed with the same platform key can provision
class JdsProvisioning : BroadcastReceiver() {

    // on broadcast receive
    // JdsFFI is the bridge from Kotlin to Rust core (where RustDesk settings live)
    override fun onReceive(context: Context, intent: Intent) {
        // full resolution broadcasting: only if the extra is present, otherwise leave it alone
        if (intent.hasExtra("provision_half_scale")) {
            JdsFFI.setLocalOption(
                "enable-android-software-encoding-half-scale",
                if (intent.getBooleanExtra("provision_half_scale", false)) "Y" else "N"
            )
        }

        // configure to our custom rust desk server - set rust key then server address
        val server = intent.getStringExtra("provision_server")?.trim().orEmpty()
        val key = intent.getStringExtra("provision_key")?.trim().orEmpty()
        if (server.isNotEmpty() && key.isNotEmpty()) {
            JdsFFI.setOption("key", key)
            JdsFFI.setOption("custom-rendezvous-server", server)
        }

        // for unattended access we need to set it to use a password and to set the password
        JdsFFI.setOption("approve-mode", "password")
        JdsFFI.setOption("verification-method", "use-permanent-password")
        // disable the floating ui window that appears on the left middle
        JdsFFI.setLocalOption("disable-floating-window", "Y")

        // set permanent password per device; setPasswordAndGetId returns the RustDesk ID
        val password = intent.getStringExtra("provision_password").orEmpty()
        val id = if (password.isNotEmpty()) JdsFFI.setPasswordAndGetId(password) else ""

        // report our RustDesk ID back to the provisioning app (if it asked for it by
        // passing provision_result_package), so it never has to scrape the UI
        val target = intent.getStringExtra("provision_result_package")
        if (id.isNotEmpty() && !target.isNullOrEmpty()) {
            context.sendBroadcast(
                Intent("com.carriez.flutter_hbb.PROVISIONED_ID")
                    .setPackage(target)
                    .putExtra("rustdesk_id", id)
            )
        }

        // enable start on boot so the service comes back online after a reboot (unattended
        // access); BootReceiver reads this flag, and JdsDevicePermissions already granted the
        // battery optimisation and overlay permissions it also requires
        context.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_START_ON_BOOT_OPT, true).apply()

        // start screen capture, same as pressing the "Screen capture" toggle but without its
        // prompts: JdsDevicePermissions already granted the screen capture AppOp, so Android
        // approves the capture request by itself
        // startForegroundService: this receiver may have cold started the process, so MainService
        // is started from the background (allowed within the broadcast's short window)
        ContextCompat.startForegroundService(
            context,
            Intent(context, MainService::class.java).setAction(ACT_INIT_MEDIA_PROJECTION_AND_SERVICE)
        )

        Log.d("JdsProvisioning", if (id.isNotEmpty()) "Provisioned, ID $id" else "Provisioned (no password set)")
    }
}
