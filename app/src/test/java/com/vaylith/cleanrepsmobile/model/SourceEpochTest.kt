package com.vaylith.cleanrepsmobile.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceEpochTest {
    @Test fun `epochs are monotonic server-compatible integers`() {
        assertEquals(0, SourceEpoch().value)
        assertEquals(1, SourceEpoch().next().value)
        assertEquals(42, SourceEpoch(41).next().value)
    }
}
