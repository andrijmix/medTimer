package com.futsch1.medtimer.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

private const val SECRET = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDEF"

class McpSettingsTest {

    @Test
    fun `should reduce a tunnel url to https and host`() {
        assertEquals("https://meds.example.com", McpSettings.normalizePublicUrl("https://meds.example.com"))
        assertEquals("https://meds.example.com", McpSettings.normalizePublicUrl("https://meds.example.com:8443/some/path?x=1#y"))
        assertEquals("https://meds.example.com", McpSettings.normalizePublicUrl("  HTTPS://Meds.Example.com/  "))
    }

    @Test
    fun `should take a url without scheme to be https`() {
        assertEquals("https://meds.example.com", McpSettings.normalizePublicUrl("meds.example.com"))
    }

    @Test
    fun `should reject urls that cannot be the address of a tunnel`() {
        assertNull(McpSettings.normalizePublicUrl(""))
        assertNull(McpSettings.normalizePublicUrl("   "))
        assertNull(McpSettings.normalizePublicUrl("http://meds.example.com"))
        assertNull(McpSettings.normalizePublicUrl("ftp://meds.example.com"))
        assertNull(McpSettings.normalizePublicUrl("https://user:pw@meds.example.com"))
        assertNull(McpSettings.normalizePublicUrl("https://bad host"))
        assertNull(McpSettings.normalizePublicUrl("https://"))
    }

    @Test
    fun `should accept ports from 1024 to 65535 only`() {
        assertEquals(1024, McpSettings.parsePort("1024"))
        assertEquals(65535, McpSettings.parsePort(" 65535 "))
        assertNull(McpSettings.parsePort("1023"))
        assertNull(McpSettings.parsePort("65536"))
        assertNull(McpSettings.parsePort("abc"))
        assertNull(McpSettings.parsePort(""))
    }

    @Test
    fun `should expose the host of the tunnel`() {
        assertEquals("meds.example.com", McpSettings(publicBaseUrl = "https://meds.example.com/").publicHost)
        assertNull(McpSettings(publicBaseUrl = "").publicHost)
    }

    @Test
    fun `should build the connector url from tunnel and secret`() {
        val settings = McpSettings(publicBaseUrl = "https://meds.example.com", secret = SECRET)

        assertEquals("https://meds.example.com/mcp/$SECRET", settings.connectorUrl)
    }

    @Test
    fun `should have no connector url while the tunnel url or the secret is missing`() {
        assertNull(McpSettings(publicBaseUrl = "", secret = SECRET).connectorUrl)
        assertNull(McpSettings(publicBaseUrl = "https://meds.example.com", secret = "").connectorUrl)
    }

    @Test
    fun `should build the local url from port and secret`() {
        assertEquals("http://127.0.0.1:9001/mcp/$SECRET", McpSettings(port = 9001, secret = SECRET).localUrl)
        assertNull(McpSettings(port = 9001, secret = "").localUrl)
    }

    @Test
    fun `should not reveal the secret in toString`() {
        assertFalse(McpSettings(secret = SECRET).toString().contains(SECRET))
    }
}
