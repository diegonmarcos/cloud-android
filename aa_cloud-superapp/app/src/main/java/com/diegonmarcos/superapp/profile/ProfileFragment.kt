package com.diegonmarcos.superapp.profile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.ui.snack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configs → Profile — three free-form fields (Name · Email · Initials)
 * bound to [ProfilePrefs]. Auto-saves on every text change (no explicit
 * Save button) — the drawer header reads from the same prefs on every
 * open, so changes are visible immediately next time the drawer slides in.
 */
class ProfileFragment : Fragment() {

    private lateinit var prefs: ProfilePrefs

    /** Gallery picker for the profile photo (round avatar). */
    private val picturePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveImage(it, isBanner = false) }
        }
    /** Gallery picker for the cover/banner photo (wide). */
    private val bannerPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveImage(it, isBanner = true) }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = ProfilePrefs(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 18); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scroll.addView(col)

        col.addView(sectionHeader(ctx, "Profile"))
        col.addView(caption(ctx, "Edit your identity — auto-saved on change. Initials show in the drawer; the rest powers the Virtual Business Card."))

        col.addView(label(ctx, "Name"))
        col.addView(field(ctx, prefs.name) { prefs.name = it })

        col.addView(label(ctx, "Email"))
        col.addView(field(ctx, prefs.email) { prefs.email = it }.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        })

        col.addView(label(ctx, "Initials"))
        col.addView(field(ctx, prefs.initials) { prefs.initials = it }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        })

        col.addView(label(ctx, "Titles  (use ' | ' to separate)"))
        col.addView(field(ctx, prefs.titles) { prefs.titles = it }.apply {
            isSingleLine = false; maxLines = 4
        })

        col.addView(label(ctx, "Company"))
        col.addView(field(ctx, prefs.company) { prefs.company = it })

        col.addView(label(ctx, "Location"))
        col.addView(field(ctx, prefs.location) { prefs.location = it })

        col.addView(label(ctx, "Website"))
        col.addView(field(ctx, prefs.website) { prefs.website = it }.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        })

        col.addView(label(ctx, "Profile picture"))
        col.addView(pickButton(ctx, prefs.pictureUri.ifBlank { "Pick from gallery…" }) {
            picturePicker.launch("image/*")
        })

        col.addView(label(ctx, "Banner photo"))
        col.addView(pickButton(ctx, prefs.bannerUri.ifBlank { "Pick from gallery…" }) {
            bannerPicker.launch("image/*")
        })

        // ── Config import ────────────────────────────────────────────────
        // Both entries live HERE now. "Import Configs" used to be its own
        // Configs-grid tile (build.json::ui.sections[config].pages[import],
        // action:import_configs) — the tile is gone and the action route it
        // used is reused verbatim below, so the launcher shortcut and the
        // radial menu still reach the same screen.
        col.addView(sectionHeader(ctx, "Config import"))
        col.addView(caption(ctx, "Import by hand from a file or a paste, or authenticate to Authelia once and pull the whole config down."))

        val importRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 4) }
        }
        importRow.addView(actionTile(ctx, "Import\nfile · paste", 0xFF7C3AED.toInt()) {
            // Same route MainActivity already owns, so chrome/back-stack
            // behaviour is identical to the old Configs tile.
            (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
                ?.onTileClicked("action:import_configs")
        })
        importRow.addView(actionTile(ctx, "OWebAuth Authelia\nAuto Import Configs", 0xFF0F766E.toInt()) {
            showAutheliaImportDialog()
        })
        col.addView(importRow)

        return scroll
    }

    // ── OWebAuth · Authelia auto-import ──────────────────────────────────

    /** Live handle to the dialog's token field, so the file picker (which
     *  must be registered on the Fragment, not the dialog) can fill it. */
    private var tokenField: EditText? = null

    /** Set when an auto-import wrote something, so the form above is
     *  redrawn with the new values once the dialog is dismissed. */
    private var importedThisSession = false

    /** "Import from file" inside the dialog — the file holds either the raw
     *  token or a JSON blob with `auth.authelia_token` (the shape already
     *  declared in build.json::ui.import_schema). */
    private val tokenFilePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            val field = tokenField ?: return@registerForActivityResult
            runCatching {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                field.setText(extractToken(text))
            }.onFailure {
                field.error = "Could not read that file: ${it.message}"
            }
        }

    /** Raw token, or `auth.authelia_token` / `authelia_token` / `token` out
     *  of a JSON file. Falls back to the trimmed file contents. */
    private fun extractToken(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            val o = org.json.JSONObject(trimmed)
            o.optJSONObject("auth")?.optString("authelia_token").orEmpty()
                .ifBlank { o.optString("authelia_token") }
                .ifBlank { o.optString("token") }
                .ifBlank { trimmed }
        }.getOrDefault(trimmed)
    }

    private fun showAutheliaImportDialog() {
        val ctx = requireContext()
        val endpoint = com.diegonmarcos.superapp.core.ConfigSyncClient.endpoint(
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
        )

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 20); setPadding(pad, dp(ctx, 12), pad, 0)
        }
        body.addView(caption(ctx, "Paste your Authelia bearer token. It is sent once as an Authorization header to:\n$endpoint\n\nThe token is never written to disk, to a log, or to any export — it is used for this one request and then dropped."))

        val input = EditText(ctx).apply {
            hint = "eyJhbGciOi…"
            setSingleLine(false)
            maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // Keep the token off the keyboard's learned-words / suggestion store.
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        tokenField = input
        body.addView(input)

        body.addView(pickButton(ctx, "Import token from file…") {
            tokenFilePicker.launch("*/*")
        })

        val status = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(ctx, 12), 0, 0)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        body.addView(status)

        val scroll = ScrollView(ctx).apply { addView(body) }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("OWebAuth · Authelia")
            .setView(scroll)
            .setPositiveButton("Authenticate & Import", null)   // wired below
            .setNegativeButton("Close", null)
            .create()

        // Redraw the form AFTER the dialog closes, never during: detach/attach
        // destroys the fragment view, which would cancel the import coroutine
        // (it runs on viewLifecycleOwner.lifecycleScope) mid-report.
        dialog.setOnDismissListener {
            tokenField = null
            if (importedThisSession) {
                importedThisSession = false
                parentFragmentManager.beginTransaction().detach(this).commitNow()
                parentFragmentManager.beginTransaction().attach(this).commitNow()
            }
        }
        dialog.setOnShowListener {
            val go = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            go.setOnClickListener {
                val token = input.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    show(status, RED, "✗ Paste a token first.")
                    return@setOnClickListener
                }
                go.isEnabled = false
                show(status, NEUTRAL, "… authenticating and fetching $endpoint")
                runImport(token, status) { go.isEnabled = true }
            }
        }
        dialog.show()
    }

    /** Fetch on IO, apply on the main thread, report either way. */
    private fun runImport(token: String, status: TextView, done: () -> Unit) {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                com.diegonmarcos.superapp.core.ConfigSyncClient.fetch(
                    baseUrl          = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
                    pathTemplate     = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
                    user             = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
                    bearer           = token,
                    connectTimeoutMs = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS,
                    readTimeoutMs    = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_READ_MS,
                )
            }
            when (outcome) {
                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Failed ->
                    show(status, RED, "✗ ${outcome.kind}\n${outcome.message}")

                is com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome.Ok -> {
                    val report = ConfigAutoImport.apply(appCtx, outcome.body)
                    val head = if (report.ok) "✓ Authenticated · ${outcome.bytes} bytes applied"
                               else "✗ Authenticated, but nothing was applied"
                    show(status, if (report.ok) GREEN else RED, "$head\n\n${report.text()}")
                    if (report.ok) {
                        view?.snack("Config imported")
                        importedThisSession = true   // form redraws on dialog dismiss
                    }
                }
            }
            done()
        }
    }

    private fun show(status: TextView, color: Int, text: String) {
        status.visibility = View.VISIBLE
        status.setTextColor(color)
        status.text = text
    }

    private fun actionTile(ctx: android.content.Context, label: String, bg: Int, onClick: () -> Unit): View =
        TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(bg)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 14), dp(ctx, 10), dp(ctx, 14))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(ctx, 4); marginStart = dp(ctx, 4) }
        }

    /** Copy the picked image into our cache dir + store the cached path
     *  in ProfilePrefs. We don't rely on the original `content://` URI
     *  surviving — the source app may revoke permission later. */
    private fun saveImage(uri: android.net.Uri, isBanner: Boolean) {
        runCatching {
            val ctx = requireContext()
            val name = if (isBanner) "profile_banner.png" else "profile_picture.png"
            val outFile = java.io.File(ctx.filesDir, name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            if (isBanner) prefs.bannerUri = outFile.absolutePath
            else          prefs.pictureUri = outFile.absolutePath
            // Re-render so the buttons show the new path.
            parentFragmentManager.beginTransaction().detach(this).commitNow()
            parentFragmentManager.beginTransaction().attach(this).commitNow()
        }
    }

    private fun pickButton(ctx: android.content.Context, currentLabel: String, onClick: () -> Unit): View {
        val tv = android.widget.TextView(ctx).apply {
            text = currentLabel
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF7C3AED.toInt())
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 4) }
        tv.layoutParams = lp
        return tv
    }

    private fun sectionHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 4))
        }

    private fun label(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            alpha = 0.85f
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        }

    private fun caption(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            setPadding(0, 0, 0, dp(ctx, 8))
        }

    private fun field(ctx: android.content.Context, initial: String, save: (String) -> Unit): EditText =
        EditText(ctx).apply {
            setText(initial)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { save(s?.toString().orEmpty()) }
            })
        }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        /** Import-status colours. GREEN is the "authenticated + applied"
         *  state the auto-import is required to show explicitly. */
        private val GREEN   = 0xFF16A34A.toInt()
        private val RED     = 0xFFDC2626.toInt()
        private val NEUTRAL = 0xFF9CA3AF.toInt()

        fun newInstance(): ProfileFragment = ProfileFragment()
    }
}
