package com.carriez.flutter_hbb

import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import ffi.FFI

internal class JdsClipboardSync(
    private val clipboardManager: ClipboardManager,
    private val syncClipboard: () -> Unit
) {
    @Volatile private var isCaptureStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isCaptureStarted && FFI.isServiceClipboardEnabled()) {
            syncClipboard()
        }
    }

    fun setCaptureStarted(started: Boolean) {
        isCaptureStarted = started
        mainHandler.post {
            if (isCaptureStarted) {
                clipboardManager.addPrimaryClipChangedListener(listener)
            } else {
                clipboardManager.removePrimaryClipChangedListener(listener)
            }
        }
    }
}
