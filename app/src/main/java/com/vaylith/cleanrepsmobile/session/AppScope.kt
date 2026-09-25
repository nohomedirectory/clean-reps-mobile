package com.vaylith.cleanrepsmobile.session

import kotlinx.coroutines.CoroutineScope
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
}
