package com.vaylith.cleanrepsmobile.api

import com.vaylith.cleanrepsmobile.model.*
import org.junit.Assert.*
import org.junit.Test

class BlockRequestTest {
    @Test fun `drill metadata carries exact target and height without inventing a default height`() {
        val body = blockRequestBody(BlockSelection(technique = KickTechnique.TEEP, targetContext = KickTarget.AIR, targetHeight = TargetHeight.HIGH))
        assertTrue(body.contains("\"technique\":\"teep\""))
        assertTrue(body.contains("\"targetContext\":\"air\""))
        assertTrue(body.contains("\"targetHeight\":\"high\""))
        assertFalse(blockRequestBody(BlockSelection()).contains("targetHeight"))
    }
}
