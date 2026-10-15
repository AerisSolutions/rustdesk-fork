package com.carriez.flutter_hbb

import android.os.Build
import android.util.Log
import android.view.ViewConfiguration
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

// JDS: Android 11 root input fallback. On API 30 gestures are replayed through `su 0 input`
// instead of dispatchGesture. RustDesk's InputService hands mouse / touch events to this first
// (one line hooks in onMouseInput / onTouchInput) - reads and updates InputService's internal
// mouseX / mouseY / leftIsDown so its own state stays consistent
class JdsRootInput {

    private val rootInputExecutor = Executors.newSingleThreadExecutor()
    private val logTag = "JdsRootInput"

    // long press threshold, same as InputService's: 100(tap) + 400(long press)
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()

    // own copy: only the root path uses it, and RustDesk's own leftIsDown path is skipped in root mode
    private var leftIsDown = false

    private var rootDragStartX = 0
    private var rootDragStartY = 0
    private var rootDragStartTime = 0L
    private var rootHasMoved = false

    private var rootPanStartX = 0
    private var rootPanStartY = 0
    private var rootPanStartTime = 0L

    private val useRootInputFallback: Boolean
        get() = Build.VERSION.SDK_INT == Build.VERSION_CODES.R

    // returns true if the event was handled here (InputService stops), false to carry on with RustDesk's own handling
    // mouseX / mouseY are the current cursor position (InputService set them just before calling)
    fun handleMouse(mask: Int, mouseX: Int, mouseY: Int): Boolean {
        if (!useRootInputFallback) {
            return false
        }

        // Left mouse pressed
        if (mask == LEFT_DOWN) {
            leftIsDown = true

            rootDragStartX = mouseX
            rootDragStartY = mouseY
            rootDragStartTime = System.currentTimeMillis()
            rootHasMoved = false

            return true
        }

        // Mouse moving while left button is held
        if (leftIsDown && (mask == LEFT_MOVE || mask == 0)) {
            val delta = abs(mouseX - rootDragStartX) + abs(mouseY - rootDragStartY)

            if (delta > 8) {
                rootHasMoved = true
            }

            return true
        }

        // Left mouse released
        if (mask == LEFT_UP) {
            if (leftIsDown) {
                leftIsDown = false

                val heldFor = System.currentTimeMillis() - rootDragStartTime

                if (rootHasMoved) {
                    // Drag
                    val duration = heldFor.coerceIn(100L, 5000L)
                    rootInput("swipe", rootDragStartX.toString(), rootDragStartY.toString(), mouseX.toString(), mouseY.toString(), duration.toString())
                } else if (heldFor >= longPressDuration) {
                    // Long press
                    val duration = heldFor.coerceIn(500L, 5000L)
                    rootInput("swipe", mouseX.toString(), mouseY.toString(), mouseX.toString(), mouseY.toString(), duration.toString())
                } else {
                    // Normal click
                    rootInput("tap", mouseX.toString(), mouseY.toString())
                }
            }

            return true
        }

        // Right mouse = Android long press
        if (mask == RIGHT_UP) {
            rootInput("swipe", mouseX.toString(), mouseY.toString(), mouseX.toString(), mouseY.toString(), "600")
            return true
        }

        return false
    }

    // returns true if the event was handled here (InputService stops), false to carry on with RustDesk's own handling
    // returns the new [mouseX, mouseY] when handled here (root mode; InputService stops), or null to carry on
    fun handleTouch(mask: Int, _x: Int, _y: Int, mouseX: Int, mouseY: Int): IntArray? {
        if (!useRootInputFallback) {
            return null
        }

        var mx = mouseX
        var my = mouseY

        when (mask) {
            TOUCH_PAN_START -> {
                mx = max(0, _x) * SCREEN_INFO.scale
                my = max(0, _y) * SCREEN_INFO.scale

                rootPanStartX = mx
                rootPanStartY = my
                rootPanStartTime = System.currentTimeMillis()
            }

            TOUCH_PAN_UPDATE -> {
                mx -= _x * SCREEN_INFO.scale
                my -= _y * SCREEN_INFO.scale

                mx = max(0, mx)
                my = max(0, my)
            }

            TOUCH_PAN_END -> {
                val duration = (System.currentTimeMillis() - rootPanStartTime).coerceIn(100L, 1000L)

                val distance = abs(mx - rootPanStartX) + abs(my - rootPanStartY)

                if (distance > 20) {
                    rootInput("swipe", rootPanStartX.toString(), rootPanStartY.toString(), mx.toString(), my.toString(), duration.toString())
                }

                // Preserve RustDesk's normal final coordinate update
                mx = max(0, _x) * SCREEN_INFO.scale
                my = max(0, _y) * SCREEN_INFO.scale
            }
        }

        return intArrayOf(mx, my)
    }

    private fun rootInput(vararg args: String) {
        rootInputExecutor.execute {
            try {
                val command = arrayOf("su", "0", "input", *args)

                val process = Runtime.getRuntime().exec(command)
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    val error = process.errorStream.bufferedReader().readText()
                    Log.e(logTag, "rootInput failed: exit=$exitCode error=$error")
                }
            } catch (e: Exception) {
                Log.e(logTag, "rootInput error", e)
            }
        }
    }
}
