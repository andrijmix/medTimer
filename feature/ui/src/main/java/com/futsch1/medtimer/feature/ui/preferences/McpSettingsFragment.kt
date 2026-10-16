package com.futsch1.medtimer.feature.ui.preferences

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.futsch1.medtimer.core.datastore.McpSettingsDataSource
import com.futsch1.medtimer.core.domain.model.McpSettings
import com.futsch1.medtimer.core.ui.preferences.MedTimerPreferenceFragment
import com.futsch1.medtimer.feature.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.futsch1.medtimer.core.ui.R as CoreUiR

/**
 * Settings of the optional MCP server. It only edits [McpSettingsDataSource]; the server itself follows those settings and this screen
 * knows nothing about it.
 */
@AndroidEntryPoint
class McpSettingsFragment : MedTimerPreferenceFragment() {

    @Inject
    lateinit var mcpSettingsDataSource: McpSettingsDataSource

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // The preferences on this screen are stored in the MCP settings file, not in the app's default preferences.
        preferenceManager.sharedPreferencesName = McpSettingsDataSource.FILE_NAME
        setPreferencesFromResource(R.xml.mcp_preferences, rootKey)

        setupEnableSwitch()
        setupPublicUrl()
        setupPort()
        setupCopyConnectorUrl()
        setupCopyLocalUrl()
        setupRegenerateSecret()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mcpSettingsDataSource.settings.collect { settings ->
                    // Why the server could not start, shown right under the switch.
                    findPreference<SwitchPreferenceCompat>(McpSettingsDataSource.ENABLED)?.summary = settings.lastError.takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    private fun setupEnableSwitch() {
        val preference = findPreference<SwitchPreferenceCompat>(McpSettingsDataSource.ENABLED) ?: return
        preference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                // Turning the server on exposes medication data, so it only happens after the user confirmed the warning.
                confirmEnable(preference)
                false
            } else {
                true
            }
        }
    }

    private fun confirmEnable(preference: SwitchPreferenceCompat) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(CoreUiR.string.mcp_enable_dialog_title)
            .setMessage(CoreUiR.string.mcp_enable_dialog_message)
            .setPositiveButton(CoreUiR.string.mcp_enable_dialog_confirm) { _, _ ->
                mcpSettingsDataSource.ensureSecret()
                preference.isChecked = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupPublicUrl() {
        val preference = findPreference<EditTextPreference>(McpSettingsDataSource.PUBLIC_BASE_URL) ?: return
        preference.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        preference.setOnPreferenceChangeListener { _, newValue ->
            val input = (newValue as String).trim()
            if (input.isEmpty()) {
                return@setOnPreferenceChangeListener true
            }
            val normalized = McpSettings.normalizePublicUrl(input)
            if (normalized == null) {
                showToast(CoreUiR.string.mcp_public_url_invalid)
            } else {
                // Store the cleaned-up form (https://host) instead of what was typed.
                preference.text = normalized
            }
            false
        }
    }

    private fun setupPort() {
        val preference = findPreference<EditTextPreference>(McpSettingsDataSource.PORT) ?: return
        preference.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
        preference.setOnPreferenceChangeListener { _, newValue ->
            val valid = McpSettings.parsePort(newValue as String) != null
            if (!valid) {
                showToast(CoreUiR.string.mcp_port_invalid)
            }
            valid
        }
    }

    private fun setupCopyConnectorUrl() {
        findPreference<Preference>(KEY_COPY_URL)?.setOnPreferenceClickListener {
            val url = currentSettings().connectorUrl
            if (url == null) {
                showToast(CoreUiR.string.mcp_copy_url_needs_tunnel)
            } else {
                copyToClipboard(url)
            }
            true
        }
    }

    private fun setupCopyLocalUrl() {
        findPreference<Preference>(KEY_COPY_LOCAL_URL)?.setOnPreferenceClickListener {
            currentSettings().localUrl?.let(::copyToClipboard)
            true
        }
    }

    private fun setupRegenerateSecret() {
        findPreference<Preference>(KEY_REGENERATE_SECRET)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(CoreUiR.string.mcp_regenerate_dialog_title)
                .setMessage(CoreUiR.string.mcp_regenerate_dialog_message)
                .setPositiveButton(CoreUiR.string.mcp_regenerate_dialog_confirm) { _, _ -> mcpSettingsDataSource.regenerateSecret() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    // The flow can lag behind a secret that was just written, so the secret is read from the data source itself.
    private fun currentSettings(): McpSettings = mcpSettingsDataSource.settings.value.copy(secret = mcpSettingsDataSource.ensureSecret())

    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText(getString(CoreUiR.string.mcp_copy_label), text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keeps the clipboard preview of Android 13+ from showing the secret on screen.
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        } else {
            // Android 13+ confirms a copy by itself.
            showToast(CoreUiR.string.mcp_url_copied)
        }
        requireContext().getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun showToast(@StringRes message: Int) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val KEY_COPY_URL = "mcp_copy_url"
        const val KEY_COPY_LOCAL_URL = "mcp_copy_local_url"
        const val KEY_REGENERATE_SECRET = "mcp_regenerate_secret"
    }
}
