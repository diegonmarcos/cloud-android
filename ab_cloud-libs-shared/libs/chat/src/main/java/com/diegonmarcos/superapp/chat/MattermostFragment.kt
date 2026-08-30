package com.diegonmarcos.superapp.chat

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
 * Comms/Mattermost page — login form when no session is saved, then a
 * categories + channels list with per-channel unread badges once
 * logged in. Mirrors libs/mail's flow: the user enters their server
 * URL (pre-filled from BuildConfig.MATTERMOST_DEFAULT_SERVER) +
 * email + password, taps Login. The session token is persisted to
 * [MattermostPrefs] (EncryptedSharedPreferences) and subsequent
 * opens skip straight to the list.
 *
 * Threading: every REST call runs on a dedicated background Thread.
 * Results are dispatched back to the main thread via View.post.
 * This avoids hauling in kotlinx-coroutines as a new lib dep.
 *
 * Data model:
 *   • Pulled per refresh: teams[0] → categories + channels + members
 *   • Derived per channel: unread = totalMsgCount − member.msgCount
 *   • Empty categories silently skipped (no dead headers).
 */
class MattermostFragment : Fragment() {

    private lateinit var prefs: MattermostPrefs
    private lateinit var root: LinearLayout
    private var listContainer: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = MattermostPrefs(ctx)
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
        root.addView(header(ctx, "Mattermost — Sign in"))
        val urlField = formField(ctx, "Server URL", prefs.serverUrl, InputType.TYPE_TEXT_VARIATION_URI)
        val emailField = formField(ctx, "Email or username", "", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val passField = formField(ctx, "Password", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(urlField); root.addView(emailField); root.addView(passField)
        val status = TextView(ctx).apply {
            setTextColor(0xAAFFFFFF.toInt()); textSize = 12f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }
        root.addView(status)
        val loginBtn = Button(ctx).apply { text = "Login" }
        root.addView(loginBtn)
        loginBtn.setOnClickListener {
            val url = urlField.findViewWithTag<EditText>("input").text.toString().trim()
            val email = emailField.findViewWithTag<EditText>("input").text.toString().trim()
            val pass = passField.findViewWithTag<EditText>("input").text.toString()
            if (url.isBlank() || email.isBlank() || pass.isBlank()) {
                status.text = "All three fields required."
                return@setOnClickListener
            }
            status.text = "Signing in…"
            loginBtn.isEnabled = false
            Thread {
                val result = runCatching {
                    MattermostClient(serverUrl = url, token = "").login(email, pass)
                }
                root.post {
                    result.onSuccess { r ->
                        prefs.serverUrl = url
                        prefs.token = r.token
                        prefs.userId = r.user.id
                        renderList(ctx)
                    }.onFailure { e ->
                        status.text = "Login failed: ${e.message}"
                        loginBtn.isEnabled = true
                    }
                }
            }.start()
        }
    }

    // ─────────────────────────── list view ───────────────────────────

    private fun renderList(ctx: Context) {
        root.removeAllViews()
        root.addView(header(ctx, "Mattermost"))
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
        val client = MattermostClient(prefs.serverUrl, prefs.token)
        val userId = prefs.userId
        Thread {
            val result = runCatching {
                val teams = client.listMyTeams()
                if (teams.isEmpty()) emptyList<Pair<MattermostCategory, List<ChannelRow>>>()
                else {
                    val team = teams.first()
                    val categories = client.listCategories(userId, team.id)
                    val channels   = client.listChannels(userId, team.id).associateBy { it.id }
                    val members    = client.listChannelMembers(userId, team.id).associateBy { it.channelId }
                    categories.map { cat ->
                        val rows = cat.channelIds.mapNotNull { id ->
                            val ch = channels[id] ?: return@mapNotNull null
                            val mem = members[id]
                            val unread = (ch.totalMsgCount - (mem?.msgCount ?: 0L)).coerceAtLeast(0L)
                            ChannelRow(ch, unread, mem?.mentionCount ?: 0L)
                        }
                        cat to rows
                    }.filter { it.second.isNotEmpty() }
                }
            }
            root.post {
                list.removeAllViews()
                result.onSuccess { groups ->
                    if (groups.isEmpty()) {
                        list.addView(TextView(ctx).apply {
                            text = "No categories yet on this team."
                            setTextColor(0xAAFFFFFF.toInt()); textSize = 13f
                        })
                        return@onSuccess
                    }
                    for ((cat, rows) in groups) {
                        list.addView(categoryHeader(ctx, cat, rows.sumOf { it.unreadCount }))
                        for (row in rows) list.addView(channelRow(ctx, row))
                    }
                }.onFailure { e ->
                    list.addView(TextView(ctx).apply {
                        text = "Load failed: ${e.message}\nTap Sign out + back in if the token expired."
                        setTextColor(0xFFFF8888.toInt()); textSize = 12f
                    })
                    Toast.makeText(ctx, "Mattermost: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ─────────────────────────── widget helpers ───────────────────────────

    private fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFE9D8FD.toInt())
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(ctx, 12))
    }

    private fun categoryHeader(ctx: Context, cat: MattermostCategory, totalUnread: Long): TextView =
        TextView(ctx).apply {
            text = if (totalUnread > 0) "${cat.displayName.ifBlank { cat.type }}  ·  $totalUnread"
                   else cat.displayName.ifBlank { cat.type }
            setTextColor(0xFFB794F4.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
        }

    private fun channelRow(ctx: Context, row: ChannelRow): View {
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
        }
        // Type prefix — # public, 🔒 private, @ DM, 👥 group DM.
        val prefix = when (row.channel.type) {
            "O" -> "# "
            "P" -> "🔒 "
            "D" -> "@ "
            "G" -> "👥 "
            else -> "?  "
        }
        ll.addView(TextView(ctx).apply {
            text = prefix + row.channel.displayName.ifBlank { row.channel.name }
            setTextColor(if (row.unreadCount > 0) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
            textSize = 14f
            if (row.unreadCount > 0) typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (row.unreadCount > 0) {
            val badgeText = if (row.mentionCount > 0)
                "${row.unreadCount}  (@${row.mentionCount})"
            else
                row.unreadCount.toString()
            ll.addView(TextView(ctx).apply {
                text = badgeText
                setTextColor(if (row.mentionCount > 0) 0xFFFF6B6B.toInt() else 0xFFB794F4.toInt())
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }
        return ll
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

    companion object { fun newInstance() = MattermostFragment() }
}
