package com.vaylith.cleanrepsmobile.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Work that must outlive the Activity: the terminal `lost` report of a capture.
 * Destroying the Activity cancels its `lifecycleScope`, which is how the
 * 2026-09-17 capture was left without its `lost`. Process death still ends
 * this scope; [PendingLostStore] covers that. Never cancelled.
 */
object AppScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The `lost` deliveries of this process, shared by every SessionController it creates. */
    val lostDeliveries = LostDeliveries()
}

/**
 * At most one `lost` delivery per capture in the whole process. A controller
 * replaced after a settings change, or by a recreated Activity, may still be
 * delivering a capture's `lost` when the new controller flushes the store; the
 * new one then joins that delivery instead of sending the report a second time.
 * Thread-safe.
 */
class LostDeliveries {
    private val running = HashMap<String, Deferred<Exception?>>()

    /** The delivery running for [captureId], or the one [start] creates. It answers null once the server has answered. */
    fun runOrJoin(captureId: String, start: () -> Deferred<Exception?>): Deferred<Exception?> = synchronized(running) {
        running.entries.removeAll { it.value.isCompleted }
        running[captureId] ?: start().also { running[captureId] = it }
    }
}
