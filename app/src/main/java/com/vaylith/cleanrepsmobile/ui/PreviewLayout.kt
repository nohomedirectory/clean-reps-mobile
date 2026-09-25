package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.model.LiveAthleteRegion

/** How the camera preview uses the screen. */
enum class PreviewMode {
    /** Fills the screen and centre-crops, like a camera app (RootEncoder `AspectRatioMode.Fill`). */
    FILL,

    /** Shows the whole frame in a stream-aspect box with a control band beside it (`AspectRatioMode.Adjust`). */
    FIT,
}

/** A rectangle in screen pixels, origin top left. It may extend past the screen on a cropped axis. */
data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/** RootEncoder's GL viewport for a surface: origin bottom left, as passed to `glViewport`. */
data class GlViewPort(val x: Int, val y: Int, val width: Int, val height: Int)

/** A point as a fraction of the full, uncropped transmitted frame (0..1 on each axis). */
data class NormalizedPoint(val x: Double, val y: Double)

/** A point in screen pixels, origin top left. */
data class ScreenPoint(val x: Double, val y: Double)

/** A box in screen pixels, origin top left. */
data class ScreenBox(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * Pure layout math for the camera preview. `streamW` x `streamH` is the upright transmitted
 * frame (for example 1280x720 in landscape, 720x1280 in portrait); the container is the
 * window area the preview may use.
 *
 * FILL (the default, owner direction) makes the `SurfaceView` the whole container, and
 * RootEncoder draws the stream with `AspectRatioMode.Fill`. FIT (the OD-3 alternative, one
 * constant: [PREVIEW_FIT]) makes the `SurfaceView` a stream-aspect box at the top left, and
 * RootEncoder draws it with `AspectRatioMode.Adjust`; the rest of the container is a control
 * band on the thumb side (right in landscape, bottom in portrait).
 *
 * [viewPort] mirrors RootEncoder 2.7.0 `SizeCalculator.calculateViewPort`, read from its
 * bytecode: the aspects are compared as floats and every size is Java integer arithmetic.
 */
object PreviewLayout {
    /** OD-3. False keeps the owner-directed FILL preview; true switches the app to FIT. */
    const val PREVIEW_FIT = false

    val DEFAULT_MODE: PreviewMode = if (PREVIEW_FIT) PreviewMode.FIT else PreviewMode.FILL

    /**
     * `SizeCalculator.calculateViewPort` for Fill (FILL) or Adjust (FIT) on a surface of
     * `surfaceW` x `surfaceH`. Fill covers the surface and centre-crops: when the stream is
     * relatively wider, the width is `streamW * surfaceH / streamH` at x = `(width - surfaceW) / -2`;
     * otherwise the height is `streamH * surfaceW / streamW` at y = `(height - surfaceH) / -2`.
     * Adjust letterboxes the other way round.
     */
    fun viewPort(mode: PreviewMode, surfaceW: Int, surfaceH: Int, streamW: Int, streamH: Int): GlViewPort {
        requireSizes(surfaceW, surfaceH, streamW, streamH)
        val streamAspect = streamW.toFloat() / streamH.toFloat()
        val surfaceAspect = surfaceW.toFloat() / surfaceH.toFloat()
        val streamRelativelyWider = streamAspect > surfaceAspect
        var x = 0
        var y = 0
        var width = surfaceW
        var height = surfaceH
        if ((mode == PreviewMode.FIT) == streamRelativelyWider) {
            height = streamH * surfaceW / streamW
            y = (height - surfaceH) / -2
        } else {
            width = streamW * surfaceH / streamH
            x = (width - surfaceW) / -2
        }
        return GlViewPort(x, y, width, height)
    }

    /** Where the `SurfaceView` goes: the whole container for FILL, a stream-aspect box for FIT. */
    fun surface(mode: PreviewMode, containerW: Int, containerH: Int, streamW: Int, streamH: Int): PixelRect {
        requireSizes(containerW, containerH, streamW, streamH)
        if (mode == PreviewMode.FILL) return PixelRect(0, 0, containerW, containerH)
        val fitted = viewPort(PreviewMode.FIT, containerW, containerH, streamW, streamH)
        return PixelRect(0, 0, fitted.width, fitted.height)
    }

    /** The control band beside a FIT box, or null when there is none (FILL, or an exact fit). */
    fun controlBand(mode: PreviewMode, containerW: Int, containerH: Int, streamW: Int, streamH: Int): PixelRect? {
        val box = surface(mode, containerW, containerH, streamW, streamH)
        return when {
            box.width < containerW -> PixelRect(box.width, 0, containerW - box.width, containerH)
            box.height < containerH -> PixelRect(0, box.height, containerW, containerH - box.height)
            else -> null
        }
    }

    /**
     * The displayed stream rectangle in screen pixels. In FILL it extends past the container on
     * the cropped axis: 2400x1080 with 1280x720 gives 2400x1350 at top -135.
     */
    fun compute(mode: PreviewMode, containerW: Int, containerH: Int, streamW: Int, streamH: Int): PixelRect {
        val surface = surface(mode, containerW, containerH, streamW, streamH)
        val port = viewPort(mode, surface.width, surface.height, streamW, streamH)
        // GL counts y from the bottom of the surface; the screen counts it from the top.
        val top = surface.top + surface.height - (port.y + port.height)
        return PixelRect(surface.left + port.x, top, port.width, port.height)
    }

    /** A point of the full transmitted frame on screen; points in a cropped margin land off screen. */
    fun toScreen(displayed: PixelRect, point: NormalizedPoint): ScreenPoint =
        ScreenPoint(displayed.left + point.x * displayed.width, displayed.top + point.y * displayed.height)

    /** The inverse of [toScreen]. */
    fun toNormalized(displayed: PixelRect, point: ScreenPoint): NormalizedPoint =
        NormalizedPoint((point.x - displayed.left) / displayed.width, (point.y - displayed.top) / displayed.height)

    fun isOnScreen(point: ScreenPoint, containerW: Int, containerH: Int): Boolean =
        point.x in 0.0..containerW.toDouble() && point.y in 0.0..containerH.toDouble()

    /**
     * A normalized box (for example a body box) on screen, clipped to the container. Null when
     * nothing of it is visible, for example when it lies wholly in a cropped margin.
     */
    fun clippedBox(
        displayed: PixelRect,
        containerW: Int,
        containerH: Int,
        topLeft: NormalizedPoint,
        bottomRight: NormalizedPoint,
    ): ScreenBox? {
        val a = toScreen(displayed, topLeft)
        val b = toScreen(displayed, bottomRight)
        val left = minOf(a.x, b.x).coerceAtLeast(0.0)
        val top = minOf(a.y, b.y).coerceAtLeast(0.0)
        val right = maxOf(a.x, b.x).coerceAtMost(containerW.toDouble())
        val bottom = maxOf(a.y, b.y).coerceAtMost(containerH.toDouble())
        return if (left < right && top < bottom) ScreenBox(left, top, right, bottom) else null
    }

    /** The analyzer's athlete region on screen, clipped like [clippedBox]. */
    fun regionOnScreen(displayed: PixelRect, containerW: Int, containerH: Int, region: LiveAthleteRegion): ScreenBox? =
        clippedBox(
            displayed,
            containerW,
            containerH,
            NormalizedPoint(region.left, region.top),
            NormalizedPoint(region.right, region.bottom),
        )

    private fun requireSizes(containerW: Int, containerH: Int, streamW: Int, streamH: Int) {
        require(containerW > 0 && containerH > 0) { "container must be positive, was ${containerW}x$containerH" }
        require(streamW > 0 && streamH > 0) { "stream must be positive, was ${streamW}x$streamH" }
    }
}
