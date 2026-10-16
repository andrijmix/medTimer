package com.futsch1.medtimer.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSecretGeneratorTest {

    @Test
    fun `should generate 43 url safe characters`() {
        val secret = McpSecretGenerator.generate()

        assertEquals(43, secret.length)
        assertTrue(secret, Regex("[A-Za-z0-9_-]{43}").matches(secret))
    }

    @Test
    fun `should generate a different secret every time`() {
        val secrets = List(50) { McpSecretGenerator.generate() }.toSet()

        assertEquals(50, secrets.size)
        assertNotEquals("", secrets.first())
    }
}
