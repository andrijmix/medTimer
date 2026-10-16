package com.futsch1.medtimer.feature.mcp.server

import com.futsch1.medtimer.core.domain.model.McpSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SECRET = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDEF"

class McpServerConfigTest {

    @Test
    fun `should build the mcp path from the secret`() {
        val config = McpServerConfig(port = 8765, secret = SECRET)

        assertEquals("/mcp/$SECRET", config.mcpPath)
    }

    @Test
    fun `should allow loopback hosts only when no public host is set`() {
        val config = McpServerConfig(port = 8765, secret = SECRET)

        assertEquals(listOf("localhost", "127.0.0.1", "[::1]"), config.allowedHosts)
    }

    @Test
    fun `should allow the lowercased public host in addition to loopback`() {
        val config = McpServerConfig(port = 8765, secret = SECRET, publicHost = "Meds.Example.com")

        assertTrue("meds.example.com" in config.allowedHosts)
        assertTrue("127.0.0.1" in config.allowedHosts)
    }

    @Test
    fun `should be valid when port and secret are well formed`() {
        assertTrue(McpServerConfig(port = 8765, secret = SECRET).isValid)
    }

    @Test
    fun `should be invalid when the port is outside 1024 to 65535`() {
        assertFalse(McpServerConfig(port = 1023, secret = SECRET).isValid)
        assertFalse(McpServerConfig(port = 65536, secret = SECRET).isValid)
    }

    @Test
    fun `should be invalid when the secret is too short`() {
        assertFalse(McpServerConfig(port = 8765, secret = "short").isValid)
    }

    @Test
    fun `should be invalid when the secret contains characters that are not url safe`() {
        assertFalse(McpServerConfig(port = 8765, secret = SECRET.dropLast(1) + "/").isValid)
        assertFalse(McpServerConfig(port = 8765, secret = SECRET.dropLast(1) + ".").isValid)
    }

    @Test
    fun `should not reveal the secret in toString`() {
        val text = McpServerConfig(port = 8765, secret = SECRET).toString()

        assertFalse(text.contains(SECRET))
    }

    @Test
    fun `should take port secret and tunnel host from the settings`() {
        val config = McpServerConfig.from(McpSettings(port = 9001, secret = SECRET, publicBaseUrl = "https://meds.example.com"))

        assertEquals(McpServerConfig(port = 9001, secret = SECRET, publicHost = "meds.example.com"), config)
    }

    @Test
    fun `should have no public host when the tunnel url is empty or unusable`() {
        assertNull(McpServerConfig.from(McpSettings(secret = SECRET, publicBaseUrl = "")).publicHost)
        assertNull(McpServerConfig.from(McpSettings(secret = SECRET, publicBaseUrl = "http://meds.example.com")).publicHost)
    }
}
