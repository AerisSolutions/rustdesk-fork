package com.carriez.flutter_hbb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.KeyEvent as KeyEventAndroid
import java.lang.reflect.Method

/**
 * Global key event injector
 *
 * Unlike RustDesk's default keyboard handling which delivers keys through an
 * IME InputConnection (API >= 33) or an editable AccessibilityNode (API < 33),
 * both of which require a focused editable field - this injects KeyEvents
 * straight into the system input pipeline via the hidden
 * InputManager.injectInputEvent(InputEvent, int). Events are dispatched to the
 * window that currently has focus, exactly like a physical/virtual keyboard,
 * regardless of whether that window contains a text field - the same
 * mechanism AnyDesk's AOSP uses
 *
 * Requires the signature/privileged permission android.permission.INJECT_EVENTS,
 * which is only granted to platform signed / privileged system apps. When the
 * permission is NOT held, isAvailable returns false and the caller transparently
 * falls back to the original accessibility/IME behavior, so the same build keeps
 * working on unprivileged installs
 */
class GlobalKeyInjector(private val context: Context) {

    companion object {
        private const val LOG_TAG = "InputInjector"

        // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        /**
         * Exempt hidden APIs from the P+ dark greylist so reflection onto
         * injectInputEvent() is allowed even when this app is not running from
         * /system (a platform signed app installed as a system app is already
         * exempt; this makes side loaded/debug builds work too)
         */
        fun relaxHiddenApiIfNeeded() {
            if (Build.VERSION.SDK_INT < 28) return
            try {
                val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
                val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
                getRuntime.isAccessible = true
                val vmRuntime = getRuntime.invoke(null)
                val setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                setExemptions.isAccessible = true
                // "L" == all classes whose signature starts with 'L' (i.e. everything)
                setExemptions.invoke(vmRuntime, arrayOf("L"))
                Log.d(LOG_TAG, "hidden API exemptions relaxed")
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "could not relax hidden API exemptions: $t")
            }
        }
    }

    private var inputManager: Any? = null

    private val injectMethod: Method? by lazy {
        try {
            val im = context.getSystemService(Context.INPUT_SERVICE)
            inputManager = im
            im?.javaClass?.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "injectInputEvent not resolvable: $t")
            null
        }
    }

    private val hasInjectPermission: Boolean
        // Referenced as a literal on purpose: android.Manifest.permission.INJECT_EVENTS
        // is a hidden constant and is not present in the public android.jar
        get() = context.checkCallingOrSelfPermission("android.permission.INJECT_EVENTS") == PackageManager.PERMISSION_GRANTED

    // True only when INJECT_EVENTS is granted AND the hidden method resolved
    val isAvailable: Boolean
        get() = hasInjectPermission && injectMethod != null

    private fun inject(event: KeyEventAndroid): Boolean {
        val m = injectMethod ?: return false
        val im = inputManager ?: return false
        return try {
            (m.invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean) ?: false
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "injectInputEvent failed: $t")
            false
        }
    }

    // Running modifier state, updated as modifier keys are injected down/up.
    // A hardware keyboard sets metaState automatically; injected events don't,
    // so we track it ourselves and OR it into every event. Without this, a
    // combo like Alt+Tab arrives as a plain Tab because the Tab-down event
    // never carries META_ALT_ON.
    private var trackedMeta = 0

    private fun metaBitFor(keyCode: Int): Int = when (keyCode) {
        KeyEventAndroid.KEYCODE_SHIFT_LEFT ->
            KeyEventAndroid.META_SHIFT_ON or KeyEventAndroid.META_SHIFT_LEFT_ON
        KeyEventAndroid.KEYCODE_SHIFT_RIGHT ->
            KeyEventAndroid.META_SHIFT_ON or KeyEventAndroid.META_SHIFT_RIGHT_ON
        KeyEventAndroid.KEYCODE_ALT_LEFT ->
            KeyEventAndroid.META_ALT_ON or KeyEventAndroid.META_ALT_LEFT_ON
        KeyEventAndroid.KEYCODE_ALT_RIGHT ->
            KeyEventAndroid.META_ALT_ON or KeyEventAndroid.META_ALT_RIGHT_ON
        KeyEventAndroid.KEYCODE_CTRL_LEFT ->
            KeyEventAndroid.META_CTRL_ON or KeyEventAndroid.META_CTRL_LEFT_ON
        KeyEventAndroid.KEYCODE_CTRL_RIGHT ->
            KeyEventAndroid.META_CTRL_ON or KeyEventAndroid.META_CTRL_RIGHT_ON
        KeyEventAndroid.KEYCODE_META_LEFT ->
            KeyEventAndroid.META_META_ON or KeyEventAndroid.META_META_LEFT_ON
        KeyEventAndroid.KEYCODE_META_RIGHT ->
            KeyEventAndroid.META_META_ON or KeyEventAndroid.META_META_RIGHT_ON
        KeyEventAndroid.KEYCODE_SYM -> KeyEventAndroid.META_SYM_ON
        KeyEventAndroid.KEYCODE_FUNCTION -> KeyEventAndroid.META_FUNCTION_ON
        else -> 0
    }

    /**
     * Rebuild [src] with fresh timestamps and a keyboard source, preserving its
     * action/keyCode/flags. Modifier keys update [trackedMeta] as they go down
     * and up, and that running modifier state is merged into every event's
     * metaState so held-modifier combos (Alt+Tab, Ctrl+key, Shift+key) are
     * recognized. Used for Map/Translate mode where RustDesk already produced a
     * proper Android KeyEvent (down or up)
     */
    fun injectKeyEvent(src: KeyEventAndroid): Boolean {
        val bit = metaBitFor(src.keyCode)
        // Update modifier state before building so a modifier's own down event
        // also carries its bit, matching hardware behavior.
        if (bit != 0) {
            when (src.action) {
                KeyEventAndroid.ACTION_DOWN -> trackedMeta = trackedMeta or bit
                KeyEventAndroid.ACTION_UP -> trackedMeta = trackedMeta and bit.inv()
            }
        }
        val meta = src.metaState or trackedMeta
        val now = SystemClock.uptimeMillis()
        val ev = KeyEventAndroid(
            now,                                  // downTime
            now,                                  // eventTime
            src.action,
            src.keyCode,
            src.repeatCount,
            meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,     // deviceId
            src.scanCode,
            src.flags,
            InputDevice.SOURCE_KEYBOARD
        )
        return inject(ev)
    }

    /** Inject a discrete down+up for [keyCode]. */
    fun injectKeyCode(keyCode: Int, metaState: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEventAndroid(
            now, now, KeyEventAndroid.ACTION_DOWN, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        val up = KeyEventAndroid(
            now, now, KeyEventAndroid.ACTION_UP, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        return inject(down) && inject(up)
    }

    /**
     * Convert [text] to KeyEvents via the virtual keyboard character map and
     * inject them. Handles the Legacy mode single character case so plain typing
     * reaches non EditText windows. Returns false if any character has no
     * mapping (e.g. emoji), so the caller can fall back to commitText
     */
    fun injectText(text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = kcm.getEvents(text.toCharArray()) ?: return false
            if (events.isEmpty()) return false
            var ok = true
            for (e in events) {
                // getEvents() already carries the right metaState (shift, etc.);
                // injectKeyEvent refreshes timestamps and sets the keyboard source
                ok = injectKeyEvent(e) && ok
            }
            ok
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "injectText failed: $t")
            false
        }
    }

    /**
     * Inject a real mouse wheel scroll as a MotionEvent(ACTION_SCROLL) from
     * SOURCE_MOUSE at (x, y). This is what a physical wheel produces, so it
     * scrolls WebViews, RecyclerViews, horizontal scrollers and everything else
     * that RustDesk's swipe gesture emulation misses. [vScroll]/[hScroll] are in
     * wheel notches: +vScroll scrolls up (away from the user), -vScroll down
     */
    fun injectScroll(x: Int, y: Int, hScroll: Float, vScroll: Float): Boolean {
        val m = injectMethod ?: return false
        val im = inputManager ?: return false
        var ev: MotionEvent? = null
        return try {
            val props = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
            val coords = MotionEvent.PointerCoords().apply {
                this.x = x.toFloat()
                this.y = y.toFloat()
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            }
            val now = SystemClock.uptimeMillis()
            ev = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL,
                1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                InputDevice.SOURCE_MOUSE, 0
            )
            (m.invoke(im, ev, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean) ?: false
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "injectScroll failed: $t")
            false
        } finally {
            ev?.recycle()
        }
    }
}
