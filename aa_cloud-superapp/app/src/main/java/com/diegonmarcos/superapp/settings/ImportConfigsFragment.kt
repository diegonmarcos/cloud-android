package com.diegonmarcos.superapp.settings
import com.diegonmarcos.superapp.R

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject

/**
 * Bulk-import private keys + URLs + tokens from a single JSON blob.
 *
 * The schema is declared in build.json::ui.import_schema and baked into
 * BuildConfig.UI_IMPORT_SCHEMA_B64 so the placeholder reflects the same
 * shape every other consumer expects (JmapPrefs, future libs:net WG,
 * ssh-agent for vault clone, etc.). Two input paths:
 *   • Open file → ACTION_OPEN_DOCUMENT picker, reads file via ContentResolver
 *   • Paste     → multi-line text field
 * Save commits the blob via [ConfigsPrefs] (EncryptedSharedPreferences).
 */
class ImportConfigsFragment : Fragment(R.layout.fragment_import_configs) {

    private lateinit var input: TextInputEditText
    private lateinit var status: TextView

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        loadFromUri(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val schemaTv     = view.findViewById<TextView>(R.id.import_schema)
        input            = view.findViewById(R.id.import_input)
        val save         = view.findViewById<MaterialButton>(R.id.import_save)
        val clear        = view.findViewById<MaterialButton>(R.id.import_clear)
        val openFile     = view.findViewById<MaterialButton>(R.id.import_from_file)
        status           = view.findViewById(R.id.import_status)

        schemaTv.text = String(Base64.decode(BuildConfig.UI_IMPORT_SCHEMA_B64, Base64.NO_WRAP))

        val prefs = ConfigsPrefs(requireContext())
        input.setText(prefs.json)

        openFile.setOnClickListener {
            // Accept anything; ACTION_OPEN_DOCUMENT respects the device file
            // picker (Files / Storage / 3rd-party). JSON or plain-text fine.
            pickFile.launch(arrayOf("application/json", "text/*", "*/*"))
        }

        save.setOnClickListener {
            val raw = input.text?.toString().orEmpty()
            try {
                JSONObject(raw)
                prefs.json = raw
                status.text = getString(R.string.import_saved, raw.length)
                view.snack(R.string.import_saved_snack)
            } catch (t: Throwable) {
                status.text = getString(R.string.import_invalid, t.message ?: "?")
            }
        }
        clear.setOnClickListener {
            prefs.clear()
            input.setText("")
            status.setText(R.string.import_cleared)
        }
    }

    private fun loadFromUri(uri: Uri) {
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw RuntimeException("empty stream")
            input.setText(text)
            status.text = getString(R.string.import_saved, text.length)
        } catch (t: Throwable) {
            status.text = getString(R.string.import_file_error, t.message ?: "?")
        }
    }

    companion object {
        fun newInstance() = ImportConfigsFragment()
    }
}
