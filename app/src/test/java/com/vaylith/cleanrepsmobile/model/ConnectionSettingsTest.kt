package com.vaylith.cleanrepsmobile.model

import org.junit.Assert.*
import org.junit.Test

class ConnectionSettingsTest {
    private fun config(api: String) = ConnectionSettings(api, "100.106.99.1:8890", "fixture-passphrase", "fixture-password")

    @Test fun `private HTTP and secure API addresses are accepted`() {
        listOf("http://100.106.99.1:4173", "http://host.tailnet.ts.net:4173", "http://192.168.1.2", "https://example.com").forEach {
            assertNull(it, config(it).validationError())
        }
    }
    @Test fun `public HTTP and deceptive private addresses are rejected`() {
        listOf("http://100.63.1.1", "http://100.128.1.1", "http://172.32.0.1", "http://192.168.1.2.example.com",
            "http://host.ts.net.evil.example", "http://example.com", "http://100.106.99.1:4173/path", "http://user@100.106.99.1").forEach {
            assertNotNull(it, config(it).validationError())
        }
    }
    @Test fun `credentials never appear in settings diagnostics`() {
        val settings = config("http://100.106.99.1:4173")
        assertFalse(settings.toString().contains("fixture"))
        assertNotNull(settings.copy(srtPassphrase = "short").validationError())
        assertNotNull(settings.copy(publishPassword = "bad&value").validationError())
    }
}
