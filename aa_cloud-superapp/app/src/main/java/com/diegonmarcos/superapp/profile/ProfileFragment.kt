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
 * Configs → Profile — the contact card, bound to [ProfilePrefs]. Auto-saves on
 * every text change (no explicit Save button) — the drawer header reads from
 * the same prefs on every open, so changes are visible immediately next time
 * the drawer slides in.
 *
 * MANDATORY NAME + EMAIL, enforced in three places that escalate rather than
 * block. The app stays completely usable if someone declines to fill them in;
 * what is not allowed is for the omission to be INVISIBLE, because a silently
 * blank contact record is indistinguishable from a working one right up until
 * the day the fleet needs it:
 *   1. inline — the field shows its own error while it is unacceptable;
 *   2. persistent — [statusBanner] sits at the top of the screen and states
 *      whether the profile is complete, and stays there until it is;
 *   3. sync gate — [ProfileSync.push] refuses to upload an incomplete profile,
 *      so a half-filled record never overwrites a good one on the server.
 * No dialog, no interstitial, nothing to dismiss and nothing gated behind it.
 *
 * PERSONAL DATA IS DISCLOSED IN PLACE. The "What is stored and where" section
 * below lists exactly which fields leave the device and offers the erase
 * action, so the answer to "what do you have on me, and take it down" is on
 * the same screen that collects it rather than in a policy nobody opens.
 */
class ProfileFragment : Fragment() {

    private lateinit var prefs: ProfilePrefs

    /** Live handle to the completeness banner so every field's TextWatcher can
     *  refresh it without rebuilding the form (which would drop focus). */
    private var statusBanner: TextView? = null

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
        col.addView(caption(ctx, "Edit your contact card — auto-saved on change. Your initials in the drawer are derived from your name; the rest powers the Virtual Business Card."))

        // Persistent completeness banner — enforcement step 2. Added first so
        // it is the first thing read, and never removed while incomplete.
        val banner = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        }
        statusBanner = banner
        col.addView(banner)
        refreshStatus()

        // ── Required ─────────────────────────────────────────────────────
        // Name and email are what make a person reachable when the app itself
        // can no longer be updated, which is the entire reason this screen
        // syncs anywhere. Everything below them is optional.
        col.addView(label(ctx, "Name  *required"))
        col.addView(requiredField(ctx, prefs.name, { prefs.name = it }) { prefs.nameError })

        col.addView(label(ctx, "Email  *required"))
        col.addView(requiredField(ctx, prefs.email, { prefs.email = it }) { prefs.emailError }.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        })

        col.addView(label(ctx, "Phone"))
        col.addView(field(ctx, prefs.phone) { prefs.phone = it }.apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        })

        col.addView(label(ctx, "Date of birth  (YYYY-MM-DD)"))
        col.addView(field(ctx, prefs.birth) { prefs.birth = it }.apply {
            hint = "1990-04-23"
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or
                android.text.InputType.TYPE_DATETIME_VARIATION_DATE
            // Advisory only — a wrong-looking date is flagged, never rejected,
            // and never blocks saving. The field is optional to begin with.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty().trim()
                    error = if (text.isEmpty() || DATE_PATTERN.matches(text)) null
                            else "Use YYYY-MM-DD"
                }
            })
        })

        col.addView(label(ctx, "City of origin"))
        col.addView(field(ctx, prefs.cityFrom) { prefs.cityFrom = it }.apply {
            hint = "Where you are from"
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

        // ── Social profiles ──────────────────────────────────────────────
        col.addView(sectionHeader(ctx, "Social profiles"))
        col.addView(caption(ctx, "Any number of {platform, link} pairs — GitHub, Mastodon, LinkedIn, whatever you use. Clearing both boxes of a row removes it."))
        col.addView(socialEditor(ctx))

        // ── Privacy ──────────────────────────────────────────────────────
        // Disclosure lives on the collecting screen on purpose: "what is held
        // about me and how do I get rid of it" should not require finding a
        // separate policy page.
        col.addView(sectionHeader(ctx, "What is stored and where"))
        col.addView(caption(ctx, PRIVACY_TEXT))
        col.addView(syncStateView(ctx))
        col.addView(actionTile(ctx, "Erase my profile (device + server)", 0xFFB91C1C.toInt()) {
            confirmErase()
        })

        // ── Config import ────────────────────────────────────────────────
        // All five entries live HERE. "Import Configs" used to be its own
        // Configs-grid tile (build.json::ui.sections[config].pages[import],
        // action:import_configs) — the tile is gone and the action route it
        // used is reused verbatim below, so the launcher shortcut and the
        // radial menu still reach the same screen.
        col.addView(sectionHeader(ctx, "Config import"))
        col.addView(caption(ctx, "One artifact, five ways in. The manual route is its own row; the four below differ only in how they prove who you are — Authelia by pasted token or by browser login, GitHub by login or by SSH key. All of them end in the same apply step, so whichever you use, the same sections are written."))

        // Row 1 — the manual route, alone and full width. It is the only entry
        // that needs no credential, so grouping it with the four authenticated
        // ones made it read as a fifth variant of the same thing.
        val manualRow = importRow(ctx)
        manualRow.addView(actionTile(ctx, "Import file · paste", 0xFF7C3AED.toInt()) {
            // Same route MainActivity already owns, so chrome/back-stack
            // behaviour is identical to the old Configs tile.
            (activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener)
                ?.onTileClicked("action:import_configs")
        })
        col.addView(manualRow)

        // Row 2 — the authenticated routes. Teal is Authelia, slate is GitHub,
        // so the pair-of-pairs is legible before reading a word.
        val authRow = importRow(ctx)
        authRow.addView(actionTile(ctx, "Import\nAuthelia\nBearer", 0xFF0F766E.toInt()) {
            showAutheliaBearerDialog()
        })
        authRow.addView(actionTile(ctx, "Import\nAuthelia\nOWebAuth", 0xFF0E7490.toInt()) {
            showAutheliaWebAuthDialog()
        })
        authRow.addView(actionTile(ctx, "Import\nGh\nOWebAuth", 0xFF334155.toInt()) {
            showGithubDeviceDialog()
        })
        authRow.addView(actionTile(ctx, "Import\nGH\nSSH", 0xFF1F2937.toInt()) {
            showGithubSshDialog()
        })
        col.addView(authRow)

        return scroll
    }

    /** Retry a queued upload whenever this screen comes back — a plausible
     *  moment for connectivity to have returned since the last failure. */
    override fun onResume() {
        super.onResume()
        ProfileSync.flush(requireContext())
        refreshStatus()
    }

    /**
     * Leaving the screen is the send point: fields auto-save as they are typed,
     * so by now prefs hold the finished card and one upload carries all of it.
     * [ProfileSync.push] itself no-ops when the profile is incomplete or no
     * endpoint is configured, and never blocks — it writes the queue and hands
     * off to a background thread.
     */
    override fun onPause() {
        super.onPause()
        ProfileSync.push(requireContext(), prefs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        statusBanner = null
    }

    // ── mandatory-field enforcement ──────────────────────────────────────

    /**
     * A [field] that additionally reports [validate]'s complaint on itself and
     * refreshes the banner on every keystroke.
     *
     * The value is SAVED even while invalid. Refusing to persist a half-typed
     * name would mean losing it on rotation, and the enforcement goal is that
     * the gap is loud, not that the text box fights the user.
     */
    private fun requiredField(
        ctx: android.content.Context,
        initial: String,
        save: (String) -> Unit,
        validate: () -> String?,
    ): EditText = field(ctx, initial) {
        save(it)
        refreshStatus()
    }.apply {
        error = validate()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            // Runs after the save watcher installed by field(), so prefs are
            // already current when validate() reads them back.
            override fun afterTextChanged(s: Editable?) { error = validate() }
        })
    }

    /**
     * Repaint the completeness banner. Called on every keystroke, so it does
     * no I/O beyond reading prefs — the upload is NOT driven from here.
     *
     * Syncing per keystroke would mean a POST per character typed. The edits
     * are already durable in prefs the moment they are typed, and the profile
     * is full-state, so the natural send point is leaving the screen
     * ([onPause]) — one upload carrying the finished card.
     */
    private fun refreshStatus() {
        val banner = statusBanner ?: return
        val complete = prefs.isComplete
        if (complete) {
            banner.setBackgroundColor(0x2216A34A)
            banner.setTextColor(GREEN)
            banner.text = if (ProfileSync.isPending(banner.context))
                "Profile complete — saved, waiting to reach the server (it will retry)."
            else
                "Profile complete — saved and synced."
        } else {
            banner.setBackgroundColor(0x22DC2626)
            banner.setTextColor(RED)
            banner.text = "Profile incomplete — " +
                listOfNotNull(prefs.nameError, prefs.emailError).joinToString("; ") + ".\n" +
                "Nothing is blocked, but without a name and an email there is no way to reach " +
                "you if an update ever breaks the app, and your profile is not synced."
        }
    }

    /** Small read-only line stating whether a document is still queued. */
    private fun syncStateView(ctx: android.content.Context): TextView =
        caption(ctx, if (ProfileSync.isPending(ctx))
            "Sync status: an edit is queued on this device and has not reached the server yet. It retries automatically."
        else
            "Sync status: nothing queued.")

    // ── social profiles ──────────────────────────────────────────────────

    /**
     * Rows of {platform, url}, plus one always-blank row to type into.
     *
     * The whole editor re-reads and re-writes the entire list on every
     * keystroke. That is O(rows) per character and completely fine at the
     * handful of rows a person actually has — and it means there is exactly
     * one representation of the list (the prefs) rather than a view-model that
     * can drift from it.
     */
    private fun socialEditor(ctx: android.content.Context): LinearLayout {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun rebuild() {
            container.removeAllViews()
            // A trailing blank row is what makes "add another" work without an
            // Add button: fill it in and the next rebuild grows a fresh one.
            val rows = prefs.socialLinks + ProfilePrefs.SocialLink("", "")
            rows.forEachIndexed { index, link ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(ctx, 4) }
                }
                fun update(platform: String, url: String) {
                    val next = rows.toMutableList()
                    next[index] = ProfilePrefs.SocialLink(platform, url)
                    prefs.socialLinks = next.filterNot { it.platform.isBlank() && it.url.isBlank() }
                }
                val platformBox = EditText(ctx).apply {
                    setText(link.platform); hint = "Platform"; setSingleLine()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val urlBox = EditText(ctx).apply {
                    setText(link.url); hint = "https://…"; setSingleLine()
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                }
                // Watchers are attached AFTER both boxes exist so each one can
                // read the other's current text instead of a stale capture.
                platformBox.addTextChangedListener(afterText {
                    update(it, urlBox.text.toString())
                })
                urlBox.addTextChangedListener(afterText {
                    update(platformBox.text.toString(), it)
                })
                row.addView(platformBox)
                row.addView(urlBox)
                container.addView(row)
            }
        }
        rebuild()
        return container
    }

    /** TextWatcher that only cares about the final text. */
    private fun afterText(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString().orEmpty()) }
    }

    // ── erasure ──────────────────────────────────────────────────────────

    /**
     * Confirm, then erase locally AND ask the server to drop the record.
     *
     * Two-step because it is destructive and irreversible; the result is
     * reported verbatim (including a failed server delete) rather than
     * optimistically claiming success, so an erasure that did not fully happen
     * can be chased instead of assumed.
     */
    private fun confirmErase() {
        val ctx = requireContext()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Erase your profile?")
            .setMessage(
                "This deletes your name, email, phone, date of birth, city of origin, " +
                "location, company, website, titles, social links and photos from this " +
                "device, and asks the server to delete its copy.\n\n" +
                "Your device also gets a new random sync id, so the old server-side " +
                "record can no longer be linked to this install.\n\nThis cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Erase") { _, _ ->
                ProfileSync.forgetMe(ctx) { message ->
                    // Callback arrives on the delete thread.
                    view?.post {
                        view?.snack(message)
                        if (isAdded) {
                            parentFragmentManager.beginTransaction().detach(this).commitNow()
                            parentFragmentManager.beginTransaction().attach(this).commitNow()
                        }
                    }
                }
            }
            .show()
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

    private fun showAutheliaBearerDialog() {
        val ctx = requireContext()
        val endpoint = com.diegonmarcos.superapp.core.ConfigSyncClient.endpoint(
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
        )
        var input: EditText? = null

        importDialog(
            title = "Authelia · Bearer token",
            positive = "Authenticate & Import",
            buildBody = { body, _ ->
                body.addView(caption(ctx, "Paste your Authelia bearer token. It is sent once as an Authorization header to:\n$endpoint\n\nThe token is never written to disk, to a log, or to any export — it is used for this one request and then dropped."))
                val field = EditText(ctx).apply {
                    hint = "eyJhbGciOi…"
                    setSingleLine(false)
                    maxLines = 4
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    // Keep the token off the keyboard's learned-words / suggestion store.
                    imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                tokenField = field
                input = field
                body.addView(field)
                body.addView(pickButton(ctx, "Import token from file…") {
                    tokenFilePicker.launch("*/*")
                })
            },
            onGo = { go, status ->
                val token = input?.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    show(status, RED, "✗ Paste a token first.")
                } else {
                    go.isEnabled = false
                    show(status, NEUTRAL, "… authenticating and fetching $endpoint")
                    runFetch(status, { go.isEnabled = true }) { fetchWithBearer(token) }
                }
            },
            onDismiss = { tokenField = null },
        ).show()
    }

    /**
     * Fetch on IO, apply on the main thread, report either way.
     *
     * Every authenticated tile ends here. The four routes differ only in the
     * [fetch] lambda — a bearer header, a session cookie, a GitHub token or a
     * clone — and share the apply step, the success/failure wording and the
     * redraw. That is the whole reason the transports were made to return one
     * [ConfigSyncClient.Outcome] type: four import buttons, one place where
     * config is actually written.
     */
    private fun runFetch(
        status: TextView,
        done: () -> Unit,
        fetch: () -> com.diegonmarcos.superapp.core.ConfigSyncClient.Outcome,
    ) {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { fetch() }
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

    /** The config route, authenticated by a pasted bearer. */
    private fun fetchWithBearer(token: String) =
        com.diegonmarcos.superapp.core.ConfigSyncClient.fetch(
            baseUrl          = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            pathTemplate     = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            user             = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
            bearer           = token,
            connectTimeoutMs = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS,
            readTimeoutMs    = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_READ_MS,
        )

    /** The same route, authenticated by the cookie a browser login left behind. */
    private fun fetchWithCookie(cookie: String) =
        com.diegonmarcos.superapp.core.ConfigSyncClient.fetchWithCookie(
            baseUrl          = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            pathTemplate     = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            user             = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
            cookie           = cookie,
            connectTimeoutMs = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS,
            readTimeoutMs    = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_READ_MS,
        )

    // ── shared dialog shell ──────────────────────────────────────────────

    /**
     * The three authenticated dialogs are the same object with a different
     * middle: a scrollable body, a status line, and a positive button that
     * deliberately does NOT dismiss on click — a failed import has to report
     * itself in place, and a dialog that vanishes on tap is how "nothing
     * happened" became the most common bug report on the bearer flow.
     */
    private fun importDialog(
        title: String,
        positive: String,
        buildBody: (LinearLayout, TextView) -> Unit,
        onGo: (go: android.widget.Button, status: TextView) -> Unit,
        onDismiss: () -> Unit = {},
    ): androidx.appcompat.app.AlertDialog {
        val ctx = requireContext()
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 20); setPadding(pad, dp(ctx, 12), pad, 0)
        }
        val status = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(ctx, 12), 0, 0)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        buildBody(body, status)
        body.addView(status)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(body) })
            .setPositiveButton(positive, null)   // wired in setOnShowListener
            .setNegativeButton("Close", null)
            .create()

        // Redraw the form AFTER the dialog closes, never during: detach/attach
        // destroys the fragment view, which would cancel an in-flight import.
        dialog.setOnDismissListener {
            onDismiss()
            if (importedThisSession) {
                importedThisSession = false
                parentFragmentManager.beginTransaction().detach(this).commitNow()
                parentFragmentManager.beginTransaction().attach(this).commitNow()
            }
        }
        dialog.setOnShowListener {
            val go = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            go.setOnClickListener { onGo(go, status) }
        }
        return dialog
    }

    // ── Authelia · browser login ─────────────────────────────────────────

    /**
     * Sign in to Authelia in an embedded WebView, then reuse that session's
     * cookie for the config request.
     *
     * Why a WebView and not a Custom Tab: the whole point is to get the cookie
     * back, and a Custom Tab's cookie jar belongs to the browser, not to this
     * app. The WebView's jar is readable through [CookieManager], which is the
     * only reason this flow can hand a credential to [fetchWithCookie].
     *
     * The cookie is never persisted by us — it lives in the WebView jar for as
     * long as the app keeps it and is dropped from memory after the request.
     */
    private fun showAutheliaWebAuthDialog() {
        val ctx = requireContext()
        val endpoint = com.diegonmarcos.superapp.core.ConfigSyncClient.endpoint(
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_BASE_URL,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_PATH,
            com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_SOURCE_USER,
        )
        var web: android.webkit.WebView? = null

        val dialog = importDialog(
            title = "Authelia · browser login",
            positive = "Import with this session",
            buildBody = { body, _ ->
                body.addView(caption(ctx, "Sign in below. Authelia will redirect back to:\n$endpoint\n\nWhen you are through the login, press Import — the session cookie the browser just earned is used for one request and then dropped. Nothing is written to disk."))
                val view = android.webkit.WebView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 380),
                    ).apply { topMargin = dp(ctx, 10) }
                    settings.javaScriptEnabled = true      // Authelia's portal is a JS app
                    settings.domStorageEnabled = true      // and keeps its state in DOM storage
                    webViewClient = android.webkit.WebViewClient()
                }
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                view.loadUrl(endpoint)
                web = view
                body.addView(view)
            },
            onGo = { go, status ->
                val cookie = android.webkit.CookieManager.getInstance().getCookie(endpoint).orEmpty()
                if (cookie.isBlank()) {
                    show(status, RED, "✗ No cookie for $endpoint yet — finish the login above first.")
                } else {
                    go.isEnabled = false
                    show(status, NEUTRAL, "… fetching $endpoint with the browser session")
                    runFetch(status, { go.isEnabled = true }) { fetchWithCookie(cookie) }
                }
            },
            // Free the WebView before the shell's redraw tears the view down.
            onDismiss = { web?.destroy(); web = null },
        )
        dialog.show()
    }

    // ── GitHub · OAuth device flow ───────────────────────────────────────

    /**
     * Approve a short code in a browser, then read the artifact out of the
     * vault repo with the token GitHub hands back.
     *
     * The device grant is used rather than a redirect flow because it needs no
     * client secret and no registered redirect URI — the app only ever holds
     * the public client_id, which is why that id sits in build.json as data.
     */
    private fun showGithubDeviceDialog() {
        val ctx = requireContext()
        var pollJob: kotlinx.coroutines.Job? = null

        val dialog = importDialog(
            title = "GitHub · browser login",
            positive = "Start",
            buildBody = { body, _ ->
                body.addView(caption(ctx,
                    if (GithubImport.deviceFlowConfigured())
                        "Press Start. GitHub will show a code to type at github.com/login/device; " +
                        "once you approve it, the artifact is read from " +
                        "${com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_REPO} " +
                        "(${com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_PATH}) and applied."
                    else
                        "This build has no GitHub OAuth client_id, so the login cannot start.\n\n" +
                        "Register an OAuth App at github.com/settings/developers with Device Flow " +
                        "enabled, put its client_id in build.json under " +
                        "ui.config_source.github_oauth.client_id, and rebuild."))
            },
            onGo = { go, status ->
                if (!GithubImport.deviceFlowConfigured()) {
                    show(status, RED, "✗ No client_id in this build — see above.")
                } else {
                    go.isEnabled = false
                    pollJob = startGithubDeviceFlow(status) { go.isEnabled = true }
                }
            },
            // Stop polling GitHub the moment the user walks away.
            onDismiss = { pollJob?.cancel() },
        )
        dialog.show()
    }

    /** Request a code, show it, poll until approved, then fetch and apply. */
    private fun startGithubDeviceFlow(status: TextView, done: () -> Unit): kotlinx.coroutines.Job =
        viewLifecycleOwner.lifecycleScope.launch {
            show(status, NEUTRAL, "… asking GitHub for a device code")
            val code = withContext(Dispatchers.IO) { GithubImport.requestDeviceCode() }
                .getOrElse {
                    show(status, RED, "✗ ${it.message}")
                    done(); return@launch
                }

            show(status, NEUTRAL,
                "Enter this code at ${code.verificationUri}\n\n    ${code.userCode}\n\n" +
                "Waiting for approval… (the code expires in ${code.expiresInSeconds / 60} min)")
            // Open the page for them; if no browser handles it the code above is
            // still on screen and selectable, so the flow is not blocked on this.
            runCatching {
                startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(code.verificationUri),
                ))
            }

            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            var interval = code.intervalSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(interval)
                when (val step = withContext(Dispatchers.IO) { GithubImport.pollForToken(code.deviceCode) }) {
                    is GithubImport.Step.Token -> {
                        show(status, NEUTRAL, "✓ Approved · reading ${com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_PATH}")
                        runFetch(status, done) { GithubImport.fetchArtifact(step.accessToken) }
                        return@launch
                    }
                    is GithubImport.Step.Failed -> {
                        show(status, RED, "✗ ${step.message}")
                        done(); return@launch
                    }
                    is GithubImport.Step.Pending -> {
                        // slow_down means GitHub wants a longer gap, and ignoring
                        // it gets the whole flow rate-limited.
                        if (step.message.contains("slow down")) interval += 5_000L
                        show(status, NEUTRAL,
                            "Enter this code at ${code.verificationUri}\n\n    ${code.userCode}\n\n${step.message}")
                    }
                }
            }
            show(status, RED, "✗ The code expired before it was approved.")
            done()
        }

    // ── GitHub · SSH key ─────────────────────────────────────────────────

    /** Live handle to the SSH key field, so the file picker can fill it. */
    private var sshKeyField: EditText? = null

    private val sshKeyFilePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            val field = sshKeyField ?: return@registerForActivityResult
            runCatching {
                val text = requireContext().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                field.setText(extractSshKey(text))
            }.onFailure { field.error = "Could not read that file: ${it.message}" }
        }

    /** A raw PEM, or `ssh.vault_repo_key` out of an artifact JSON — the shape
     *  build.json::ui.import_schema already declares, so a previously exported
     *  config can bootstrap the next import. */
    private fun extractSshKey(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            val o = org.json.JSONObject(trimmed)
            o.optJSONObject("ssh")?.optString("vault_repo_key").orEmpty()
                .ifBlank { o.optString("vault_repo_key") }
                .ifBlank { trimmed }
                // A JSON string carries \n as an escape; JSch needs real newlines.
                .replace("\\n", "\n")
        }.getOrDefault(trimmed)
    }

    private fun showGithubSshDialog() {
        val ctx = requireContext()
        val repo = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_REPO
        val path = com.diegonmarcos.superapp.BuildConfig.UI_CONFIG_GIT_PATH
        var passField: EditText? = null

        val dialog = importDialog(
            title = "GitHub · SSH key",
            positive = "Clone & Import",
            buildBody = { body, _ ->
                body.addView(caption(ctx, "Paste the private key that has read access to $repo, or import it from a file (a previously exported config works — the key is read from ssh.vault_repo_key).\n\nThis route CLONES the repository: GitHub offers no file-read over SSH, so the whole repo is fetched shallow and bare into the cache and deleted immediately after $path is read. Nothing is checked out. If you have a token, the GitHub login tile fetches only the one file."))

                val key = EditText(ctx).apply {
                    hint = "-----BEGIN OPENSSH PRIVATE KEY-----"
                    setSingleLine(false); maxLines = 6
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                sshKeyField = key
                body.addView(key)

                body.addView(pickButton(ctx, "Import key from file…") {
                    sshKeyFilePicker.launch("*/*")
                })

                body.addView(label(ctx, "Passphrase  (leave empty if the key has none)"))
                val pass = EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                passField = pass
                body.addView(pass)
            },
            onGo = { go, status ->
                val key = sshKeyField?.text?.toString()?.trim().orEmpty()
                val pass = passField?.text?.toString().orEmpty()
                if (key.isEmpty()) {
                    show(status, RED, "✗ Paste or import a private key first.")
                } else {
                    go.isEnabled = false
                    show(status, NEUTRAL, "… cloning $repo over SSH (shallow, bare)")
                    val cacheDir = requireContext().cacheDir
                    runFetch(status, { go.isEnabled = true }) {
                        GitSshVault.fetchArtifact(cacheDir, key, pass)
                    }
                }
            },
            onDismiss = { sshKeyField = null },
        )
        dialog.show()
    }

    /** One row of import tiles. */
    private fun importRow(ctx: android.content.Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 6) }
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

        /** Advisory shape check for the birth field. Range/real-calendar
         *  validity is deliberately not checked — the field is optional and a
         *  false rejection is worse than a typo here. */
        private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /**
         * In-screen disclosure. Kept specific — field names, destination and
         * the erasure route — because a vague notice is what makes people
         * unable to reason about their own data.
         */
        private const val PRIVACY_TEXT =
            "Your name, email, phone, date of birth, city of origin, location, company, " +
            "website, titles and social links are stored on this device and mirrored to " +
            "the constellation server over HTTPS, so the fleet operator can contact you " +
            "out-of-band when an update breaks the app and it can no longer fix itself. " +
            "That is the only reason this is collected.\n\n" +
            "Your photos stay on this device and are never uploaded. Your profile is " +
            "identified by a random id generated on this install — not by any device, " +
            "SIM or advertising identifier. It is not synced until name and email are " +
            "filled in, and it is never written to logs or crash reports.\n\n" +
            "Erase removes it here and asks the server to delete its copy."

        fun newInstance(): ProfileFragment = ProfileFragment()
    }
}
