package com.carriez.flutter_hbb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

// Sheriff broadcasts its Full / Half choice before a remote control session.
// JDS: MainService registers / unregisters this in onCreate / onDestroy, and its "half_scale"
// branch reads JdsHalfScale.choice which is stored by this object
object JdsHalfScale {

    // null = no choice made, so RustDesk's own half-scale setting is used as normal
    // @Volatile: set on the main thread (receiver), read on Rust's thread ("half_scale")
    @Volatile var choice: Boolean? = null
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            choice = intent.getBooleanExtra("half_scale", false)
            Log.d("JdsHalfScale", "Sheriff chose half scale: $choice (applies when the next session starts)")
        }
    }

    // called from MainService.onCreate
    fun register(context: Context) {
        // listen for the Sheriff agent's Full / Half choice (exported: it comes from another app)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("com.carriez.flutter_hbb.SET_HALF_SCALE"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    // called from MainService.onDestroy
    fun unregister(context: Context) {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
