package com.futsch1.medtimer.core.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.futsch1.medtimer.core.domain.model.McpSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpSettingsDataSourceTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var dataSource: McpSettingsDataSource

    @Before
    fun setUp() {
        sharedPreferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test_mcp_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().commit()
        dataSource = McpSettingsDataSource(sharedPreferences, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun `should be disabled with the default port and no secret when nothing is stored`() {
        assertEquals(McpSettings(), dataSource.settings.value)
    }

    @Test
    fun `should publish the enabled flag when it is changed`() {
        dataSource.setEnabled(true)

        assertTrue(dataSource.settings.value.enabled)
    }

    @Test
    fun `should read the port that the settings screen stored as a string`() {
        sharedPreferences.edit().putString(McpSettingsDataSource.PORT, "9000").commit()

        assertEquals(9000, dataSource.settings.value.port)
    }

    @Test
    fun `should fall back to the default port when the stored port is out of range or not a number`() {
        sharedPreferences.edit().putString(McpSettingsDataSource.PORT, "80").commit()
        assertEquals(McpSettings.DEFAULT_PORT, dataSource.settings.value.port)

        sharedPreferences.edit().putString(McpSettingsDataSource.PORT, "abc").commit()
        assertEquals(McpSettings.DEFAULT_PORT, dataSource.settings.value.port)
    }

    @Test
    fun `should read the public base url that the settings screen stored`() {
        sharedPreferences.edit().putString(McpSettingsDataSource.PUBLIC_BASE_URL, "https://meds.example.com").commit()

        assertEquals("https://meds.example.com", dataSource.settings.value.publicBaseUrl)
    }

    @Test
    fun `should generate a secret on first request and keep returning it`() {
        val first = dataSource.ensureSecret()
        val second = dataSource.ensureSecret()

        assertEquals(43, first.length)
        assertEquals(first, second)
        assertEquals(first, dataSource.settings.value.secret)
    }

    @Test
    fun `should replace the secret when it is regenerated`() {
        val old = dataSource.ensureSecret()

        val new = dataSource.regenerateSecret()

        assertNotEquals(old, new)
        assertEquals(new, dataSource.settings.value.secret)
    }

    @Test
    fun `should publish and clear the last error`() {
        dataSource.setLastError("Port 8765 is already in use")
        assertEquals("Port 8765 is already in use", dataSource.settings.value.lastError)

        dataSource.setLastError("")
        assertEquals("", dataSource.settings.value.lastError)
    }

    @Test
    fun `should not reveal the secret in toString`() {
        val secret = dataSource.ensureSecret()

        assertFalse(dataSource.settings.value.toString().contains(secret))
    }
}
