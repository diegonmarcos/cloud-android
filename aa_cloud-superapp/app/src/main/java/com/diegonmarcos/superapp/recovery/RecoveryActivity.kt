package com.diegonmarcos.superapp.recovery

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.diegonmarcos.superapp.updater.BootstrapInstall
import com.diegonmarcos.superapp.updater.BuildAge
import com.diegonmarcos.superapp.updater.BuildConfig as UpdaterBuildConfig
import com.diegonmarcos.superapp.updater.Fleet
import kotlin.concurrent.thread

/**
 * RECOVERY — the screen a stranded device can always reach.
 *
 * Every other install surface in this app assumes something works: the
 * Constellation store assumes a privileged shell channel, auto-update assumes
 * a worker that runs and a channel it can use, and both of those assume the
 * installed build is new enough to know how to do the right thing. When that
 * chain breaks, the device cannot take the fix that would repair it — it is
 * stuck by construction, and until now the only way out was a person with a
 * cable, which does not exist for most of a fleet this size.
 *
 * This screen assumes NOTHING but a network. It downloads through the same
 * verified source ladder, and hands the bytes to the ordinary Android package
 * installer. The user sees the standard confirmation sheet and nothing else.
 *
 * Deliberately its OWN Activity rather than a page inside the launcher: it is
 * the tap target for the home banner AND for the advisory notification, and a
 * notification that has to route through the launcher's tile grammar to reach
 * a screen is a notification that lands on Home when that grammar changes.
 *
 * Programmatic views, no layout XML — this screen must keep working when the
 * rest of the app does not, and every resource it does not need is one less
 * thing that can be missing.
 */
class RecoveryActivity : Activity() {

    private lateinit var list: LinearLayout

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0A0C.toInt())
            setPadding(dp(14), dp(28), dp(14), dp(14))
        }
        root.addView(TextView(this).apply {
            text = "Recovery — direct install"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Downloads the published build and hands it to Android's own " +
                "installer. Works with no pairing, no Shizuku and no special " +
                "access — you will just be asked to confirm each install."
            setTextColor(0xFF9A9AA4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(6), 0, dp(4))
        })
        if (!BootstrapInstall.canRequestInstalls(this)) root.addView(TextView(this).apply {
            // Not a gate — Android offers the grant inline on the first attempt.
            // Saying so first is the difference between an unexpected settings
            // screen and an expected one.
            text = "Android will first ask you to allow this app to install apps. " +
                "That is expected; allow it and the install continues."
            setTextColor(0xFFD08A2A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, dp(4))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        setContentView(root)
        render(intent?.getStringExtra(EXTRA_APP_ID))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent?.getStringExtra(EXTRA_APP_ID))
    }

    /** [preselect] first, then everything else. The banner and the notification
     *  both know WHICH app is stuck, and making the user hunt for it in a
     *  40-entry list is how a one-tap fix becomes a support request. */
    private fun render(preselect: String?) {
        list.removeAllViews()
        val fleet = runCatching { Fleet.parse(UpdaterBuildConfig.CONSTELLATION_FLEET_B64) }
            .getOrDefault(emptyList())
            .filter { !it.blocked }
        if (fleet.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No fleet manifest in this build — nothing to recover from here."
                setTextColor(0xFF9A9AA4.toInt())
            })
            return
        }
        // Self ALWAYS first after any preselection: this app is the one whose
        // breakage takes every other recovery path down with it.
        val ordered = fleet.sortedBy {
            when {
                it.id == preselect -> 0
                it.pkg == packageName -> 1
                else -> 2
            }
        }
        ordered.forEach { list.addView(row(it, highlight = it.id == preselect)) }
    }

    private fun row(app: Fleet.App, highlight: Boolean): ViewGroup {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF16161A.toInt())
                if (highlight) setStroke(dp(2), 0xFFE0553F.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        card.addView(TextView(this).apply {
            text = app.label + if (app.pkg == packageName) "  (this app)" else ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        // WHAT YOU HAVE, stated before anything is downloaded. The version the
        // fetch will actually install is only knowable after the fetch, so the
        // status line below is rewritten with both numbers once it lands —
        // "is it really newer?" must be answerable from the screen, not taken
        // on trust because a button said Install.
        val installedCode = BootstrapInstall.installedVersionCode(this, app.pkg)
            ?: app.altId?.let { BootstrapInstall.installedVersionCode(this, it) }
        val status = TextView(this).apply {
            text = if (installedCode == null) "not installed"
                   else "installed: build ${BuildAge.describe(installedCode)}"
            setTextColor(0xFF9A9AA4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(3), 0, dp(6))
        }
        card.addView(status)

        val go = TextView(this).apply {
            text = "Download + install directly"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat(); setColor(0xFF1F6F43.toInt())
            }
        }
        go.setOnClickListener {
            go.isEnabled = false
            status.text = "fetching…"
            thread(name = "recovery-${app.id}") {
                val r = BootstrapInstall.launch(this@RecoveryActivity, app)
                runOnUiThread {
                    go.isEnabled = true
                    r.onSuccess { c ->
                        // Report the EVIDENCE, not just success: "digest
                        // sha256:…" and "size 32012393 B" are different
                        // guarantees and the screen should not imply they are
                        // the same one.
                        status.text = c.versionLine() +
                            (c.behindBy?.let { "\n$it" } ?: "") +
                            "\nverified by ${c.evidence}" +
                            if (c.isNewer) "" else "\nNOTE: this is not newer than what you have."
                    }.onFailure {
                        status.text = "could not fetch: ${it.message}"
                    }
                }
            }
        }
        card.addView(go)
        return card
    }

    override fun onResume() {
        super.onResume()
        // Reaching this screen at all means the user acted on the advisory, but
        // it is the INSTALL that resolves it — so nothing is cleared here.
        // Advisory.recordSuccess fires from the install path itself.
        RecoveryNotifier.cancelAll(this)
    }

    companion object {
        const val EXTRA_APP_ID = "recovery_app_id"

        /** Deep link straight at [appId]'s row. Null opens the list. */
        fun intent(ctx: Context, appId: String?): Intent =
            Intent(ctx, RecoveryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (appId != null) putExtra(EXTRA_APP_ID, appId) }
    }
}
