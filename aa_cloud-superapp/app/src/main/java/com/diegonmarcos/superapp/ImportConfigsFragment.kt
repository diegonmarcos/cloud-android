package com.diegonmarcos.superapp

import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.TextView
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
 * ssh-agent for vault clone, etc.). One pasted JSON → [ConfigsPrefs]
 * encrypts it at rest; per-field consumers parse what they need lazily.
 */
class ImportConfigsFragment : Fragment(R.layout.fragment_import_configs) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val schemaTv = view.findViewById<TextView>(R.id.import_schema)
        val input    = view.findViewById<TextInputEditText>(R.id.import_input)
        val save     = view.findViewById<MaterialButton>(R.id.import_save)
        val clear    = view.findViewById<MaterialButton>(R.id.import_clear)
        val status   = view.findViewById<TextView>(R.id.import_status)

        schemaTv.text = String(Base64.decode(BuildConfig.UI_IMPORT_SCHEMA_B64, Base64.NO_WRAP))

        val prefs = ConfigsPrefs(requireContext())
        input.setText(prefs.json)

        save.setOnClickListener {
            val raw = input.text?.toString().orEmpty()
            try {
                // Reject anything that isn't a JSON object — caller pasted
                // a schema example or stray text.
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

    companion object {
        fun newInstance() = ImportConfigsFragment()
    }
}
