package com.vaylith.cleanrepsmobile.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMtxSrtConfigTest {
    @Test fun `creates the Cloud OBS MediaMTX canonical publish URL`() {
        val endpoint = MediaMtxSrtConfig("100.64.0.20", "passphrase1", "publisher-secret").endpoint()
        assertEquals(
            "srt://100.64.0.20:8890?mode=caller&streamid=#!::m=publish,r=million-kicks-camera,u=publisher,s=publisher-secret&passphrase=passphrase1&latency=1000000&pkt_size=1316",
            endpoint,
        )
    }

    @Test fun `rejects an endpoint disguised as a host and wrong source path`() {
        assertTrue(MediaMtxSrtConfig("srt://bad", "passphrase1", "publisher-secret").validationError()!!.contains("host"))
        assertTrue(MediaMtxSrtConfig("host", "passphrase1", "publisher-secret", "another-path").validationError()!!.contains("Canonical"))
    }

    @Test fun `allows an explicit SRT port`() {
        val config = MediaMtxSrtConfig("tailnet.example:8890", "passphrase1", "publisher-secret")
        assertNull(config.validationError())
        assertTrue(config.endpoint().startsWith("srt://tailnet.example:8890?"))
    }
}
