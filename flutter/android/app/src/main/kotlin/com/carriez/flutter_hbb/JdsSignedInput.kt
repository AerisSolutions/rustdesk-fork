package com.carriez.flutter_hbb

import android.content.Context
import android.util.Log
import android.view.KeyEvent as KeyEventAndroid
import hbb.MessageOuterClass.KeyEvent

// JDS: global key injection and the mouse wheel (requires signed perms). RustDesk's InputService hands keys and wheel
// events to this first (one line hooks in onKeyEvent / onMouseInput) - anything not handled
// here carries on through RustDesk's original handling
class JdsSignedInput(context: Context) {

    private val keyInjector: GlobalKeyInjector by lazy { GlobalKeyInjector(context.applicationContext) }

    // Prefer global injection whenever INJECT_EVENTS is actually granted;
    // otherwise fall through to the original accessibility/IME handling.
    private val useGlobalKeyInjection: Boolean
        get() = keyInjector.isAvailable

    // called from InputService.onServiceConnected
    fun onServiceConnected(logTag: String) {
        // Allow reflection onto InputManager.injectInputEvent on API 28+ even
        // when not running from /system. Harmless if it fails or is unneeded.
        GlobalKeyInjector.relaxHiddenApiIfNeeded()
        Log.d(logTag, "global key injection available: ${keyInjector.isAvailable}")
    }

    // =============================================
    // Mouse wheel / scroll.
    // On this build RustDesk delivers the wheel as mask == 3 with the scroll
    // delta in _x/_y (confirmed via logcat: mask=3 _x=0 _y=1 / _y=-1), NOT as
    // the hardcoded WHEEL_UP/WHEEL_DOWN mask constants (which never match here,
    // leaving those branches dead). Read the delta from _x/_y and inject a
    // real ACTION_SCROLL. _y>0 = scroll up (away from user)
    // =============================================
    fun handleWheel(_x: Int, _y: Int, mouseX: Int, mouseY: Int) {
        if (useGlobalKeyInjection) {
            // Sign only: one notch per event, direction from the delta.
            val vScroll = when {
                _y > 0 -> 1f
                _y < 0 -> -1f
                else -> 0f
            }
            val hScroll = when {
                _x > 0 -> 1f
                _x < 0 -> -1f
                else -> 0f
            }
            if (vScroll != 0f || hScroll != 0f) {
                keyInjector.injectScroll(mouseX, mouseY, hScroll, vScroll)
            }
        }
    }

    // =============================================
    // Global key injection (INJECT_EVENTS)
    // Delivers keys to the focused window regardless of whether a text field
    // is focused. Only taken when INJECT_EVENTS is granted - on failure we
    // fall through to the original field only delivery below.
    // =============================================
    // returns true if the key was delivered here (InputService stops), false to carry on with RustDesk's own handling
    fun tryInjectKey(keyEvent: KeyEvent, ke: KeyEventAndroid?, textToCommit: String?): Boolean {
        if (useGlobalKeyInjection) {
            val injectableKe = ke   // local val: clean, guaranteed smart cast below
            if (textToCommit != null && !keyEvent.hasSeq()) {
                // Legacy mode single character typed globally (e.g. the "info"
                // in "info + Enter"). hasSeq() text is real field text and is
                // intentionally left to the commit path below
                if (keyInjector.injectText(textToCommit)) {
                    return true
                }
                // else: unmappable char -> fall through to commitText
            } else if (injectableKe != null && textToCommit == null) {
                // Map/Translate mode: a real keycode event (letters, Enter,
                // Tab, arrows, modifiers...). Inject it as delivered. RustDesk
                // sends a key either as a separate down and up, or as a single
                // "press" (a full tap). For a press the event is the down, so we
                // add the matching up afterwards to complete the tap.
                val injected = keyInjector.injectKeyEvent(injectableKe)
                if (injected && keyEvent.getPress()) {
                    val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, injectableKe.keyCode)
                    keyInjector.injectKeyEvent(actionUpEvent)
                }
                if (injected) {
                    return true
                }
                // else: injection failed -> fall through to original handling
            }
        }
        return false
    }
}
