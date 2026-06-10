package com.diegonmarcos.superapp.ops.dagu

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Infos/Dagu — GitHub-Actions-style list of every registered DAG on
 * workflows.diegonmarcos.com with a coloured status dot per row +
 * relative last-run timestamp + schedule. Mirrors the Mattermost
 * page's flow:
 *
 *   • No token saved → login form (URL pre-filled from BuildConfig,
 *     bearer token pasted by the user from
 *     vault/A0_keys/providers/authelia/oauth/get_token.py output).
 *   • Token saved → fetch GET /api/v1/dags on a background Thread,
 *     render the list. Refresh button re-fetches. Sign out wipes
 *     prefs.
 *
 * Threading: every REST call runs on a dedicated background Thread.
 * Results dispatch back to the main thread via View.post. Same
 * pattern MattermostFragment / JmapClient uses — keeps libs/ops
 * coroutine-free.
 */
class DaguFragment : Fragment() {

    private lateinit var prefs: DaguPrefs
    private lateinit var root: LinearLayout
    private var listContainer: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = DaguPrefs(ctx)
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        if (prefs.isLoggedIn()) renderList(ctx) else renderLoginForm(ctx)
        return root
    }

    // ─────────────────────────── login form ───────────────────────────

    private fun renderLoginForm(ctx: Context) {
        root.removeAllViews()
        root.addView(header(ctx, "Dagu — Sign in"))
        root.addView(small(ctx,
            "Paste an Authelia bearer token. Generate one on the laptop with:\n" +
                "  python ~/git/vault/A0_keys/providers/authelia/oauth/get_token.py\n" +
                "  jq -r .access_token ~/git/vault/A0_keys/providers/authelia/oauth/authelia_tokens.json"))
        val urlField = formField(ctx, "Server URL", prefs.serverUrl, InputType.TYPE_TEXT_VARIATION_URI)
        val tokenField = formField(ctx, "Bearer token", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val labelField = formField(ctx, "Label (optional)", "", InputType.TYPE_CLASS_TEXT)
        root.addView(urlField); root.addView(tokenField); root.addView(labelField)
        val status = TextView(ctx).apply {
            setTextColor(0xAAFFFFFF.toInt()); textSize = 12f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }
        root.addView(status)
        val saveBtn = Button(ctx).apply { text = "Save & test" }
        root.addView(saveBtn)
        saveBtn.setOnClickListener {
            val url = urlField.findViewWithTag<EditText>("input").text.toString().trim()
            val token = tokenField.findViewWithTag<EditText>("input").text.toString().trim()
            val label = labelField.findViewWithTag<EditText>("input").text.toString().trim()
            if (url.isBlank() || token.isBlank()) {
                status.text = "Server URL and bearer token both required."
                return@setOnClickListener
            }
            status.text = "Testing…"
            saveBtn.isEnabled = false
            Thread {
                val result = runCatching { DaguClient(url, token).listDags() }
                root.post {
                    result.onSuccess {
                        prefs.serverUrl = url
                        prefs.bearerToken = token
                        prefs.userLabel = label
                        renderList(ctx)
                    }.onFailure { e ->
                        status.text = "Test failed: ${e.message}"
                        saveBtn.isEnabled = true
                    }
                }
            }.start()
        }
    }

    // ─────────────────────────── list view ───────────────────────────

    private fun renderList(ctx: Context) {
        root.removeAllViews()
        root.addView(header(ctx, "Dagu — Workflow runs"))
        if (prefs.userLabel.isNotBlank()) {
            root.addView(small(ctx, "signed in as ${prefs.userLabel}"))
        }
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val refreshBtn = Button(ctx).apply { text = "Refresh" }
        val signOutBtn = Button(ctx).apply { text = "Sign out" }
        controls.addView(refreshBtn)
        controls.addView(signOutBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(ctx, 8) })
        root.addView(controls)
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 8); setPadding(0, p, 0, p)
        }
        listContainer = list
        scroll.addView(list)
        root.addView(scroll)
        refreshBtn.setOnClickListener { loadAndRender(ctx) }
        signOutBtn.setOnClickListener {
            prefs.clear()
            renderLoginForm(ctx)
        }
        loadAndRender(ctx)
    }

    private fun loadAndRender(ctx: Context) {
        val list = listContainer ?: return
        list.removeAllViews()
        list.addView(TextView(ctx).apply {
            text = "Loading…"; setTextColor(0xAAFFFFFF.toInt()); textSize = 13f
        })
        val client = DaguClient(prefs.serverUrl, prefs.bearerToken)
        Thread {
            val result = runCatching { client.listDags() }
            root.post {
                list.removeAllViews()
                result.onSuccess { resp ->
                    if (resp.dags.isEmpty()) {
                        list.addView(TextView(ctx).apply {
                            text = "No DAGs registered."
                            setTextColor(0xAAFFFFFF.toInt()); textSize = 13f
                        })
                        return@onSuccess
                    }
                    // Sort by last-run timestamp desc — most recent on top,
                    // never-run DAGs at the bottom. Same default GHA shows.
                    val sorted = resp.dags.sortedByDescending { it.lastRun?.finishedAtMs ?: 0L }
                    for (d in sorted) list.addView(dagRow(ctx, d))
                }.onFailure { e ->
                    list.addView(TextView(ctx).apply {
                        text = "Load failed: ${e.message}\nIf the token expired, Sign out + paste a fresh one."
                        setTextColor(0xFFFF8888.toInt()); textSize = 12f
                    })
                    Toast.makeText(ctx, "Dagu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ─────────────────────────── widget helpers ───────────────────────────

    private fun dagRow(ctx: Context, d: DaguDag): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10))
        }
        // Status dot — coloured circle drawn as a TextView with a
        // GradientDrawable background. Cheap + no asset.
        val dot = TextView(ctx).apply {
            val sz = dp(ctx, 12)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { topMargin = dp(ctx, 4) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(statusColor(d.lastRun?.status ?: 0))
            }
        }
        row.addView(dot)
        val text = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(ctx).apply {
            this.text = d.displayLabel
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        val subParts = mutableListOf<String>()
        subParts += statusLabel(d.lastRun?.status ?: 0)
        d.lastRun?.finishedAtMs?.takeIf { it > 0L }?.let { subParts += "  ·  ${relativeAgo(it)}" }
        if (d.schedule.isNotBlank()) subParts += "  ·  ⏰ ${d.schedule}"
        text.addView(TextView(ctx).apply {
            this.text = subParts.joinToString("")
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 12f
        })
        if (d.description.isNotBlank()) {
            text.addView(TextView(ctx).apply {
                this.text = d.description
                setTextColor(0x88FFFFFF.toInt())
                textSize = 11f
            })
        }
        row.addView(text)
        return row
    }

    /** Dagu status int → row dot colour. Lifted from Dagu's own
     *  internal/scheduler/scheduler.go status constants. */
    private fun statusColor(status: Int): Int = when (status) {
        1 -> 0xFF1565C0.toInt()  // running — blue
        2 -> 0xFFEF5350.toInt()  // error — red
        3 -> 0xFFFFA726.toInt()  // cancel — orange
        4 -> 0xFF2E7D32.toInt()  // success — green
        5 -> 0xFF9E9E9E.toInt()  // skipped — grey
        6 -> 0xFFFFCA28.toInt()  // partial-success — amber
        else -> 0xFF555555.toInt() // unknown / no runs — dim grey
    }

    private fun statusLabel(status: Int): String = when (status) {
        1 -> "running"
        2 -> "error"
        3 -> "cancelled"
        4 -> "success"
        5 -> "skipped"
        6 -> "partial-success"
        else -> "no runs yet"
    }

    /** "5 m ago" / "2 h ago" / "3 d ago" — same readable shape GHA
     *  uses for the workflows column. */
    private fun relativeAgo(epochMs: Long): String {
        val deltaSec = ((System.currentTimeMillis() - epochMs) / 1000).coerceAtLeast(0)
        return when {
            deltaSec < 60        -> "$deltaSec s ago"
            deltaSec < 3600      -> "${deltaSec / 60} m ago"
            deltaSec < 86400     -> "${deltaSec / 3600} h ago"
            deltaSec < 86400 * 7 -> "${deltaSec / 86400} d ago"
            else                 -> {
                val days = deltaSec / 86400
                "$days d ago"
            }
        }
    }

    private fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFE9D8FD.toInt())
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(ctx, 12))
    }

    private fun small(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0x99FFFFFF.toInt())
        textSize = 11f
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    private fun formField(ctx: Context, label: String, initial: String, inputType: Int): View {
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
        }
        ll.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 11f
        })
        ll.addView(EditText(ctx).apply {
            tag = "input"
            setText(initial)
            setInputType(inputType)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        })
        return ll
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = DaguFragment() }
}
