package com.vaylith.cleanrepsmobile.media

/** Camera lens, never the athlete's anatomical side. Both lenses send unmirrored video. */
enum class CameraFacing(val label: String) {
    BACK("Rear"),
    FRONT("Front");

    val opposite: CameraFacing get() = if (this == BACK) FRONT else BACK

    companion object {
        /** A missing, obsolete or unavailable preference falls back to a lens that exists. */
        fun restored(storedName: String?, available: Set<CameraFacing>): CameraFacing =
            entries.firstOrNull { it.name == storedName && it in available }
                ?: entries.firstOrNull { it in available }
                ?: BACK
    }
}
