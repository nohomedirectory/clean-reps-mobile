package com.vaylith.cleanrepsmobile.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceEpochTest {
    @Test fun `epochs are monotonic server-compatible integers`() {
        assertEquals(0, SourceEpoch().value)
        assertEquals(1, SourceEpoch().next().value)
        assertEquals(42, SourceEpoch(41).next().value)
    }

    @Test fun `manual evidence window is bounded to the current canonical source epoch`() {
        assertEquals(ManualEvidenceWindow(0, 1_000), manualEvidenceWindow(1_000))
        assertEquals(ManualEvidenceWindow(1_500, 4_000), manualEvidenceWindow(4_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manual evidence window rejects negative source time`() {
        manualEvidenceWindow(-1)
    }
}
