package com.vaylith.cleanrepsmobile.media

import android.view.Surface
import android.view.SurfaceHolder

/**
 * The publisher's own [SurfaceHolder.Callback]. It replaces RootEncoder's
 * `startPreview(view, true)` callback, whose `surfaceDestroyed` called
 * `stopPreview(true)` and removed the callbacks, so the preview stayed black
 * after Home, Power or another app. Creation only records the surface; the
 * preview starts from `surfaceChanged`, which always follows creation with the
 * real size.
 */
internal class PreviewSurfaceBinder(private val coordinator: PreviewCoordinator<Surface>) : SurfaceHolder.Callback {
    private var holder: SurfaceHolder? = null

    /** Installs this callback on [holder]; a surface that is already valid is used at once. */
    fun install(holder: SurfaceHolder) {
        if (this.holder === holder) return
        uninstall()
        this.holder = holder
        holder.addCallback(this)
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            val frame = holder.surfaceFrame
            coordinator.surfaceAvailable(surface, frame.width(), frame.height())
        }
    }

    /** Removes the callback and detaches the preview; a running stream is not affected. */
    fun uninstall() {
        val current = holder ?: return
        current.removeCallback(this)
        holder = null
        coordinator.surfaceLost()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = coordinator.surfaceAvailable(holder.surface, 0, 0)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (coordinator.hasSurface) {
            coordinator.surfaceChanged(width, height)
        } else {
            coordinator.surfaceAvailable(holder.surface, width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = coordinator.surfaceLost()
}
