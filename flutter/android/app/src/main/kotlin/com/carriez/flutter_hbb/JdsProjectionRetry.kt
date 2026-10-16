package com.carriez.flutter_hbb

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

// JDS: at boot, asking for screen capture straight away can be lost (the system isn't ready to
// show / auto grant it yet), leaving RustDesk without video until someone opens it. So when
// MainService is started from boot, the first request waits 10 seconds, then is repeated every
// 5 seconds (3 attempts in total) until capture is ready. Normal starts are left to upstream.
// JDS: MainService creates this, calls startFromBoot in onStartCommand and cancel in onDestroy
class JdsProjectionRetry(
    private val logTag: String,
    private val isReady: () -> Boolean, // MainService: mediaProjection != null || _isReady
    private val request: () -> Unit // MainService: requestMediaProjection()
) {
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0

    private val attempt = object : Runnable {
        override fun run() {
            // capture is running (an earlier attempt worked): stop
            if (isReady()) return

            attempts++
            Log.d(logTag, "Requesting MediaProjection, attempt $attempts")
            request()

            if (attempts < MAX_ATTEMPTS) handler.postDelayed(this, RETRY_DELAY_MS)
        }
    }

    // true if this start came from boot and the request is now scheduled here,
    // false to let MainService request straight away as upstream does
    fun startFromBoot(intent: Intent): Boolean {
        if (!intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) return false

        handler.removeCallbacks(attempt)
        attempts = 0
        handler.postDelayed(attempt, BOOT_DELAY_MS)
        return true
    }

    // called from MainService.onDestroy
    fun cancel() {
        handler.removeCallbacks(attempt)
    }

    companion object {
        private const val BOOT_DELAY_MS = 10_000L
        private const val RETRY_DELAY_MS = 5_000L
        private const val MAX_ATTEMPTS = 3
    }
}
