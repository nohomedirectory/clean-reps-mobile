package com.vaylith.cleanrepsmobile.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraThemeTest {
    private lateinit var preferences: SharedPreferences

    @Before fun clearAppearance() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences(CameraThemeStore.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @Test fun `an install or upgrade without appearance preferences starts in Space`() {
        assertEquals(CameraThemePreferences(CameraThemeOption.SPACE, true), CameraThemeStore(preferences).load())
    }

    @Test fun `every theme and disabled motion restore through a new store instance`() {
        CameraThemeOption.entries.forEach { theme ->
            CameraThemeStore(preferences).save(CameraThemePreferences(theme, motionEnabled = false))
            assertEquals(CameraThemePreferences(theme, false), CameraThemeStore(preferences).load())
        }
        assertEquals(setOf("theme", "motion"), preferences.all.keys)
    }

    @Test fun `a new composition can restore a theme without enabling motion again`() {
        val first = CameraThemeState(CameraThemeStore(preferences).load(), CameraThemeStore(preferences))
        first.setMotionEnabled(false)
        first.selectTheme(CameraThemeOption.SLIME)
        val restored = CameraThemeState(CameraThemeStore(preferences).load(), CameraThemeStore(preferences))
        assertEquals(CameraThemeOption.SLIME, restored.theme)
        assertFalse(restored.preferences.motionEnabled)
        restored.setMotionEnabled(true)
        assertEquals(CameraThemeOption.SLIME, CameraThemeStore(preferences).load().theme)
        assertTrue(CameraThemeStore(preferences).load().motionEnabled)
    }

    @Test fun `unknown names and obsolete stored types recover without crashing`() {
        preferences.edit().putString(CameraThemeStore.THEME, "REMOVED_THEME").putBoolean(CameraThemeStore.MOTION, false).commit()
        assertEquals(CameraThemePreferences(CameraThemeOption.SPACE, false), CameraThemeStore(preferences).load())
        preferences.edit().putInt(CameraThemeStore.THEME, 42).putString(CameraThemeStore.MOTION, "true").commit()
        assertEquals(CameraThemePreferences(), CameraThemeStore(preferences).load())
    }

    @Test fun `a failed preference commit leaves the displayed choice unchanged and reports failure`() {
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by preferences.edit() {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
                override fun commit(): Boolean = false
            }
        }
        val state = CameraThemeState(CameraThemePreferences(), CameraThemeStore(failing))
        state.selectTheme(CameraThemeOption.PSYCHEDELIC)
        assertEquals(CameraThemeOption.SPACE, state.theme)
        assertEquals(CameraThemeOption.SPACE, CameraThemeStore(preferences).load().theme)
        assertNotNull(state.saveError)
    }

    @Test fun `text stays readable on buttons and the brightest possible decorative panel`() {
        CameraThemeOption.entries.forEach { theme ->
            val palette = theme.palette
            // All artwork receives one final scrim. White is a brighter adversarial pixel
            // than any actual star, gradient or overlapping blob beneath it.
            val artworkAlpha = if (theme == CameraThemeOption.CLASSIC) 0f else THEME_ART_ALPHA
            val brightestPanel = Color.White.copy(alpha = artworkAlpha).compositeOver(palette.panel)
            listOf(palette.panel, brightestPanel).forEach { background ->
                assertTrue("$theme main text contrast", contrast(palette.text, background) >= 4.5)
                assertTrue("$theme secondary text contrast", contrast(palette.mutedText, background) >= 4.5)
                assertTrue("$theme link contrast", contrast(palette.accent, background) >= 4.5)
            }
            assertTrue("$theme button contrast", contrast(palette.onAccent, palette.accent) >= 4.5)
        }
    }

    @Test fun `Classic keeps the existing orange while new palettes are distinct`() {
        assertEquals(Color(0xFFFF6D00), CameraThemeOption.CLASSIC.palette.accent)
        assertEquals(4, CameraThemeOption.entries.map { it.palette.accent }.distinct().size)
    }

    private fun contrast(first: Color, second: Color): Double {
        val lighter = maxOf(first.luminance(), second.luminance()).toDouble()
        val darker = minOf(first.luminance(), second.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }
}
