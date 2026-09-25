package com.vaylith.cleanrepsmobile.model

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class DrillSelectionStoreTest {
    /** In-memory SharedPreferences: the last change to a key in an editor wins, as on Android. */
    private class FakePreferences(var commitSucceeds: Boolean = true) : SharedPreferences {
        val values = linkedMapOf<String, String>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
        override fun contains(key: String?): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = unused()
        override fun getInt(key: String?, defValue: Int): Int = unused()
        override fun getLong(key: String?, defValue: Long): Long = unused()
        override fun getFloat(key: String?, defValue: Float): Float = unused()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = unused()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = unused()
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = unused()

        private inner class Editor : SharedPreferences.Editor {
            private val changes = linkedMapOf<String, String?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = null
                return this
            }
            override fun commit(): Boolean {
                if (!commitSucceeds) return false
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() {
                commit()
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = unused()
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = unused()
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = unused()
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = unused()
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = unused()
            override fun clear(): SharedPreferences.Editor = unused()
        }

        private fun unused(): Nothing = throw UnsupportedOperationException("not used by the drill store")
    }

    private val preferences = FakePreferences()
    private val store = SharedPreferencesDrillSelectionStore(preferences)

    @Test fun `the default drill is Teep, right, hanging bag and an empty store loads it`() {
        val expected = BlockSelection(KickTechnique.TEEP, KickSide.RIGHT, KickTarget.HANGING_BAG, targetHeight = null)
        assertEquals(expected, BlockSelection())
        assertEquals(expected, store.load())
        assertEquals("drill-selection", SharedPreferencesDrillSelectionStore.FILE_NAME)
    }

    @Test fun `the last choice persists as wire values`() {
        val choice = BlockSelection(KickTechnique.TEEP, KickSide.LEFT, KickTarget.AIR, TargetHeight.HIGH)
        store.save(choice)
        assertEquals(
            mapOf("technique" to "teep", "side" to "left", "targetContext" to "air", "targetHeight" to "high"),
            preferences.values,
        )
        assertEquals(choice, SharedPreferencesDrillSelectionStore(preferences).load())
    }

    @Test fun `saving without a height clears the stored height`() {
        store.save(BlockSelection(targetHeight = TargetHeight.LOW))
        store.save(BlockSelection(targetHeight = null))
        assertFalse(preferences.contains("targetHeight"))
        assertNull(store.load().targetHeight)
    }

    @Test fun `a stored technique that is not auto-judged loads as Teep and keeps the rest`() {
        store.save(BlockSelection(KickTechnique.SIDE_KICK, KickSide.LEFT, KickTarget.STANDING_BAG, TargetHeight.MIDDLE))
        assertEquals("side_kick", preferences.values["technique"])
        assertEquals(
            BlockSelection(KickTechnique.TEEP, KickSide.LEFT, KickTarget.STANDING_BAG, TargetHeight.MIDDLE),
            store.load(),
        )
        preferences.values["technique"] = "roundhouse"
        assertEquals(KickTechnique.TEEP, store.load().technique)
    }

    @Test fun `unreadable stored values fall back to the default`() {
        preferences.values.putAll(
            mapOf("technique" to "flying_knee", "side" to "RIGHT ", "targetContext" to "moon", "targetHeight" to "top"),
        )
        assertEquals(BlockSelection(), store.load())
    }

    @Test fun `a failed save is reported instead of being ignored`() {
        preferences.commitSucceeds = false
        assertThrows(IllegalStateException::class.java) { store.save(BlockSelection()) }
    }

    @Test fun `only Teep is auto-judged this round`() {
        assertEquals(listOf(KickTechnique.TEEP), KickTechnique.entries.filter { it.autoJudged })
    }
}
