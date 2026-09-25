package com.vaylith.cleanrepsmobile.ui

import com.vaylith.cleanrepsmobile.model.CaptureOrientation

/**
 * How the phone is physically held, in `OrientationEventListener` degrees: 0 in the natural
 * orientation, 90 with the left side up, 180 upside down, 270 with the right side up.
 */
enum class Quadrant(val degrees: Int) {
    DEG_0(0),
    DEG_90(90),
    DEG_180(180),
    DEG_270(270);

    /**
     * The `Surface.ROTATION_*` index (0..3) the display takes when the phone is held this way.
     * The display rotates against the phone: 90 degrees (left side up) is `ROTATION_270`, and
     * 270 degrees is `ROTATION_90`, the mapping CameraX documents for `OrientationEventListener`.
     */
    val displayRotation: Int get() = ((360 - degrees) % 360) / 90

    companion object {
        fun forDisplayRotation(displayRotation: Int): Quadrant {
            require(displayRotation in 0..3) { "display rotation must be 0..3, was $displayRotation" }
            return entries.first { it.displayRotation == displayRotation }
        }
    }
}

/**
 * Pure quantizer over `OrientationEventListener.onOrientationChanged` readings.
 *
 * - A reading counts for a quadrant only within [HYSTERESIS_DEGREES] (30 degrees) of its
 *   nominal angle. The 30-degree gaps between those zones never change anything, so a phone held
 *   near a diagonal keeps its last orientation. For example, from 0 the phone must reach 60
 *   degrees to count for 90, and from 90 it must come back to 30 to count for 0.
 * - A new quadrant becomes [stable] only after its readings have held for [STABILITY_MS]
 *   (1.5 s) without a reading outside its zone.
 * - A phone lying flat ([ORIENTATION_UNKNOWN]) never changes the orientation; it only cancels a
 *   pending change.
 *
 * `nowMs` is a monotonic clock such as `SystemClock.elapsedRealtime()`.
 */
class PhysicalOrientation(initial: Quadrant? = null) {
    var stable: Quadrant? = initial
        private set

    private var candidate: Quadrant? = null
    private var candidateSinceMs = 0L

    /** Feeds one reading and returns the stable quadrant, or null before the first one. */
    fun update(degrees: Int, nowMs: Long): Quadrant? {
        val zone = if (degrees < 0) null else zoneOf(degrees % 360)
        if (zone == null || zone == stable) {
            candidate = null
            return stable
        }
        if (zone != candidate || nowMs < candidateSinceMs) {
            candidate = zone
            candidateSinceMs = nowMs
        }
        if (nowMs - candidateSinceMs >= STABILITY_MS) {
            stable = zone
            candidate = null
        }
        return stable
    }

    companion object {
        /** `OrientationEventListener.ORIENTATION_UNKNOWN`: the phone is lying (nearly) flat. */
        const val ORIENTATION_UNKNOWN = -1
        const val HYSTERESIS_DEGREES = 30
        const val STABILITY_MS = 1_500L

        /** The quadrant whose nominal angle is within [HYSTERESIS_DEGREES] of `degrees`, if any. */
        fun zoneOf(degrees: Int): Quadrant? = Quadrant.entries.firstOrNull { quadrant ->
            val difference = Math.floorMod(degrees - quadrant.degrees, 360)
            minOf(difference, 360 - difference) <= HYSTERESIS_DEGREES
        }

        /**
         * The banner shown while live when the phone has been turned away from the orientation
         * the video was started in; the video keeps its orientation until it is stopped.
         * Null when not live, before a stable reading, or while the phone is held as at the start.
         */
        fun turnedBanner(live: Boolean, lockedDisplayRotation: Int, stream: CaptureOrientation, physical: Quadrant?): String? {
            if (!live || physical == null) return null
            if (physical == Quadrant.forDisplayRotation(lockedDisplayRotation)) return null
            return "Phone turned - video stays ${stream.wireValue}. Stop video to switch."
        }
    }
}
