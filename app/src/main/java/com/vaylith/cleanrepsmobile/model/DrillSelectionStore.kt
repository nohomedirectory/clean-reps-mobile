package com.vaylith.cleanrepsmobile.model

import android.content.Context
import android.content.SharedPreferences

/** The athlete's last drill choice, kept on this phone only. */
interface DrillSelectionStore {
    fun load(): BlockSelection
    fun save(selection: BlockSelection)
}

/**
 * Stores the drill in the SharedPreferences file [FILE_NAME] as wire values. A
 * stored technique that is not auto-judged loads as Teep, because only Teep is
 * selectable this round; an unreadable value falls back to the [BlockSelection]
 * default. Intent and camera profile are fixed and never stored.
 */
class SharedPreferencesDrillSelectionStore(private val preferences: SharedPreferences) : DrillSelectionStore {
    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    override fun load(): BlockSelection {
        val default = BlockSelection()
        val technique = KickTechnique.entries.firstOrNull { it.wireValue == preferences.getString(TECHNIQUE, null) }
        return default.copy(
            technique = technique?.takeIf { it.autoJudged } ?: KickTechnique.TEEP,
            side = KickSide.entries.firstOrNull { it.name.lowercase() == preferences.getString(SIDE, null) } ?: default.side,
            targetContext = KickTarget.entries.firstOrNull { it.wireValue == preferences.getString(TARGET, null) }
                ?: default.targetContext,
            targetHeight = TargetHeight.entries.firstOrNull { it.wireValue == preferences.getString(HEIGHT, null) },
        )
    }

    override fun save(selection: BlockSelection) {
        val editor = preferences.edit()
            .putString(TECHNIQUE, selection.technique.wireValue)
            .putString(SIDE, selection.side.name.lowercase())
            .putString(TARGET, selection.targetContext.wireValue)
        val height = selection.targetHeight
        if (height == null) editor.remove(HEIGHT) else editor.putString(HEIGHT, height.wireValue)
        check(editor.commit()) { "Could not save the drill on this phone" }
    }

    companion object {
        const val FILE_NAME = "drill-selection"
        private const val TECHNIQUE = "technique"
        private const val SIDE = "side"
        private const val TARGET = "targetContext"
        private const val HEIGHT = "targetHeight"
    }
}
