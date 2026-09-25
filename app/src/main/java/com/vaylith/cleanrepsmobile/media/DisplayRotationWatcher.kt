package com.vaylith.cleanrepsmobile.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * Reports the display rotation (`Surface.ROTATION_0` to `ROTATION_270`, i.e.
 * 0..3) whenever it changes. The Activity handles rotation itself (manifest
 * `configChanges`), and a 180-degree flip between landscape and reverse
 * landscape changes neither size nor configuration, so it never reaches
 * `onConfigurationChanged`; the display listener sees every rotation.
 *
 * Call [start] and [stop] on the listener's thread (the main thread for the
 * [Context] constructor). After [stop] the listener is unregistered and
 * dropped, and a callback already in flight is ignored.
 */
class DisplayRotationWatcher internal constructor(
    private val display: WatchedDisplay,
    private val onRotationChanged: (Int) -> Unit,
) {
    constructor(context: Context, onRotationChanged: (Int) -> Unit) : this(
        AndroidWatchedDisplay(context.getSystemService(DisplayManager::class.java), Handler(Looper.getMainLooper())),
        onRotationChanged,
    )

    private var listener: DisplayManager.DisplayListener? = null
    private var lastRotation: Int? = null

    /** Registers once and returns the current rotation, or null while the display is unavailable. */
    fun start(): Int? {
        if (listener != null) return lastRotation
        lastRotation = currentRotation()
        val registered = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (listener !== this || displayId != display.id) return
                val rotation = currentRotation() ?: return
                if (rotation == lastRotation) return
                lastRotation = rotation
                onRotationChanged(rotation)
            }
        }
        listener = registered
        display.register(registered)
        return lastRotation
    }

    fun stop() {
        val registered = listener ?: return
        listener = null
        lastRotation = null
        display.unregister(registered)
    }

    /** A fresh read, independent of the listener. */
    fun currentRotation(): Int? = display.rotation()?.takeIf { it in 0..3 }
}

internal interface WatchedDisplay {
    val id: Int
    fun rotation(): Int?
    fun register(listener: DisplayManager.DisplayListener)
    fun unregister(listener: DisplayManager.DisplayListener)
}

private class AndroidWatchedDisplay(
    private val manager: DisplayManager,
    private val handler: Handler,
) : WatchedDisplay {
    override val id = Display.DEFAULT_DISPLAY
    override fun rotation(): Int? = manager.getDisplay(id)?.rotation
    override fun register(listener: DisplayManager.DisplayListener) = manager.registerDisplayListener(listener, handler)
    override fun unregister(listener: DisplayManager.DisplayListener) = manager.unregisterDisplayListener(listener)
}
