package com.vaylith.cleanrepsmobile.media

import com.vaylith.cleanrepsmobile.model.SourceEpoch

/**
 * The only object permitted to publish the phone camera. A real implementation must feed one
 * encoded source to MediaMTX `million-kicks-camera`; it must not fan out to recorder/analyzer.
 */
interface CanonicalSourcePublisher {
    suspend fun start(epoch: SourceEpoch): PublisherResult
    suspend fun stop()
    val isAvailable: Boolean
}

sealed interface PublisherResult {
    data class Live(val sourceId: String) : PublisherResult
    data class Blocked(val reason: String) : PublisherResult
    data class Failed(val reason: String) : PublisherResult
}

/** Honest baseline until a vetted Android SRT/WebRTC encoder is integrated and exercised. */
class UnavailableCanonicalPublisher(
    private val missingConfig: List<String>,
) : CanonicalSourcePublisher {
    override val isAvailable = false
    override suspend fun start(epoch: SourceEpoch): PublisherResult = PublisherResult.Blocked(
        if (missingConfig.isNotEmpty()) "Missing ${missingConfig.joinToString()}; source publisher unavailable"
        else "No vetted Android SRT/WebRTC publisher is linked; source publisher unavailable",
    )
    override suspend fun stop() = Unit
}
