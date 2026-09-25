package com.vaylith.cleanrepsmobile.media

import android.hardware.display.DisplayManager
import org.junit.Assert.*
import org.junit.Test

class DisplayRotationWatcherTest {
    private class FakeDisplay(var rotation: Int?) : WatchedDisplay {
        override val id = 0
        val listeners = mutableListOf<DisplayManager.DisplayListener>()
        var registrations = 0

        override fun rotation() = rotation
        override fun register(listener: DisplayManager.DisplayListener) {
            registrations += 1
            listeners += listener
        }
        override fun unregister(listener: DisplayManager.DisplayListener) {
            listeners -= listener
        }

        /** The platform calls onDisplayChanged for any display property, not only rotation. */
        fun change(rotation: Int?, displayId: Int = id) {
            this.rotation = rotation
            listeners.toList().forEach { it.onDisplayChanged(displayId) }
        }
    }

    private val display = FakeDisplay(rotation = 0)
    private val reported = mutableListOf<Int>()
    private val watcher = DisplayRotationWatcher(display) { reported += it }

    @Test fun `start registers one listener and returns the current rotation`() {
        display.rotation = 1
        assertEquals(1, watcher.start())
        assertEquals(1, watcher.start())
        assertEquals(1, display.registrations)
        assertEquals(1, display.listeners.size)
        assertEquals(emptyList<Int>(), reported)
    }

    @Test fun `every rotation change is reported including 180-degree flips`() {
        watcher.start()
        listOf(1, 3, 1, 2, 0).forEach { display.change(it) }
        // 1 -> 3 and 3 -> 1 are landscape/reverse-landscape flips, 0 -> 2 the portrait flip.
        display.change(2)
        assertEquals(listOf(1, 3, 1, 2, 0, 2), reported)
    }

    @Test fun `unchanged rotations, other displays and unusable readings are not reported`() {
        display.rotation = 1
        watcher.start()
        display.change(1)
        display.change(3, displayId = 7)
        display.change(null)
        display.change(5)
        display.change(-1)
        assertEquals(emptyList<Int>(), reported)
        display.change(3)
        display.change(3)
        display.change(0)
        assertEquals(listOf(3, 0), reported)
    }

    @Test fun `stop unregisters, ignores a callback already in flight and can start again`() {
        watcher.start()
        val first = display.listeners.single()
        watcher.stop()
        assertEquals(emptyList<DisplayManager.DisplayListener>(), display.listeners)
        display.rotation = 1
        first.onDisplayChanged(display.id)
        assertEquals(emptyList<Int>(), reported)
        watcher.stop()

        assertEquals(1, watcher.start())
        assertEquals(2, display.registrations)
        assertNotSame(first, display.listeners.single())
        display.change(3)
        assertEquals(listOf(3), reported)
    }

    @Test fun `a display that is unavailable at start reports its first real rotation`() {
        display.rotation = null
        assertNull(watcher.start())
        display.change(0)
        assertEquals(listOf(0), reported)
    }

    @Test fun `currentRotation reads fresh and rejects out-of-range values`() {
        display.rotation = 2
        assertEquals(2, watcher.currentRotation())
        display.rotation = 4
        assertNull(watcher.currentRotation())
        display.rotation = null
        assertNull(watcher.currentRotation())
        assertEquals(0, display.registrations)
    }
}
