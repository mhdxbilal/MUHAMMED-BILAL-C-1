package com.continental.player

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.continental.player.base.BaseActivity
import com.continental.player.data.SettingsRepository

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val settings by lazy { SettingsRepository.getInstance(requireContext()) }

        private val folderTreePicker =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    settings.downloadCustomTreeUri = uri.toString()
                    settings.downloadLocationMode = "CUSTOM"
                    findPreference<Preference>("pref_dl_location_mode")?.let {
                        (it as? androidx.preference.ListPreference)?.value = "CUSTOM"
                    }
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("pref_manage_hidden_folders")?.setOnPreferenceClickListener {
                showHiddenFoldersDialog()
                true
            }

            findPreference<Preference>("pref_dl_choose_folder")?.setOnPreferenceClickListener {
                folderTreePicker.launch(null)
                true
            }
        }

        private fun showHiddenFoldersDialog() {
            val hidden = settings.hiddenFolders.toList()
            if (hidden.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "No hidden folders", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val labels = hidden.map { it.substringAfterLast('/') }.toTypedArray()
            val checked = BooleanArray(hidden.size) { true } // checked = stays hidden

            AlertDialog.Builder(requireContext())
                .setTitle("Hidden folders — uncheck to unhide")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Done") { _, _ ->
                    hidden.forEachIndexed { index, path ->
                        if (!checked[index]) settings.unhideFolder(path)
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }
}
