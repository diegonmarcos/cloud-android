package com.diegonmarcos.superapp.recovery

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.updater.Advisory
import com.diegonmarcos.superapp.updater.BuildConfig as UpdaterBuildConfig
import com.diegonmarcos.superapp.updater.Fleet

/**
 * THE PRIMARY WARNING SURFACE: an in-app card on the home screen.
 *
 * ## Why not (only) a notification
 * A system notification can be swiped away, silenced, or blocked outright at
 * the OS level, and its channel importance is frozen at creation — so the one
 * message that MUST be seen is carried by the one mechanism the user cannot
 * turn off from outside the app. Opening the app is not optional for someone
 * who uses it as their launcher, so this is seen. The notification still
 * exists ([RecoveryNotifier]) because it reaches a user who never opens the
 * app, which is a different person with the same problem; neither replaces the
 * other.
 *
 * ## Why it lives on the Activity, not a home fragment
 * There are several home surfaces (3D, grouped, drawer, tabs, fan) and any of
 * them can be the user's default. A banner added to one of them is a banner
 * most of the fleet never sees. `ShellActivity` is the single ancestor of
 * both `HomeActivity` and `MainActivity`, and `R.id.overlay_container` is the
 * full-bleed host that already spans every one of those fragments — so hosting
 * it here is one anchor instead of five that can drift.
 *
 * It is added as a plain VIEW, never a Fragment: `ShellActivity` decides
 * whether the home screen is showing by asking whether `overlay_container`
 * holds a fragment, and a banner that made the launcher think an overlay was
 * open would break navigation to fix a warning.
 */
object RecoveryBanner {

    private const val TAG_VIEW = "recovery-banner"

    /**
     * Re-render the banner in [activity]. Safe to call on every resume — it
     * removes whatever it added last time and adds nothing when there is
     * nothing to say, so the steady state costs one view lookup.
     */
    fun refresh(activity: Activity) {
        val host = activity.findViewById<FrameLayout>(R.id.overlay_container) ?: return
        host.findViewWithTag<View>(TAG_VIEW)?.let { host.removeView(it) }
        val fleet = runCatching { Fleet.parse(UpdaterBuildConfig.CONSTELLATION_FLEET_B64) }
            .getOrDefault(emptyList())
        val item = Advisory.current(activity, fleet).firstOrNull() ?: return
        host.addView(build(activity, item))
    }

    /** Keep the banner live while the activity is on screen: an advisory can
     *  arrive from a background pass or the feed while the user is looking at
     *  the home screen, and a warning that only appears on the NEXT launch is
     *  a warning delayed by however long the phone stays unlocked. */
    fun attach(activity: Activity) {
        Advisory.setListener { activity.runOnUiThread { refresh(activity) } }
        refresh(activity)
    }

    fun detach() = Advisory.setListener(null)

    private fun build(activity: Activity, item: Advisory.Item): View {
        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
                activity.resources.displayMetrics).toInt()
        }
        val accent = when (item.severity) {
            Advisory.Severity.STUCK -> 0xFFE0553F.toInt()
            Advisory.Severity.WARN -> 0xFFD08A2A.toInt()
            Advisory.Severity.INFO -> 0xFF3A7BD5.toInt()
        }
        val card = LinearLayout(activity).apply {
            tag = TAG_VIEW
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF21A1A1F.toInt())
                setStroke(dp(2), accent)
            }
            // Clickable so the card swallows its own touches instead of letting
            // them through to the home screen underneath — a banner you can tap
            // THROUGH is a banner that launches an app when you meant to fix one.
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP
                setMargins(dp(10), dp(6), dp(10), 0)
            }
        }
        card.addView(TextView(activity).apply {
            text = item.title
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        if (item.detail.isNotBlank()) card.addView(TextView(activity).apply {
            text = item.detail.trim()
            setTextColor(0xFFBFBFC8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        // The advisory published to the feed is the only one whose words can be
        // newer than this build; saying where it came from is the difference
        // between "the app is confused" and "someone has looked at this".
        if (item.source == "feed") card.addView(TextView(activity).apply {
            text = "advisory from the fleet channel"
            setTextColor(0xFF7A7A85.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(3), 0, 0)
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(button(activity, "Fix it — install directly", accent) {
            // A FEED advisory may name an APK URL directly, and that link is
            // the true last resort: it does not go through this app's sources,
            // this app's fleet manifest, or this app's idea of which tag is
            // current — all of which are as old as the stranded build. Handing
            // it to the browser means the download and the install are both
            // done by software that is not the broken one.
            val link = item.link
            if (link != null) runCatching {
                activity.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(link)))
            }.onFailure { activity.startActivity(RecoveryActivity.intent(activity, item.appId)) }
            else activity.startActivity(RecoveryActivity.intent(activity, item.appId))
        })
        row.addView(button(activity, "Later", 0xFF3A3A45.toInt()) {
            // SESSION ONLY. See Advisory.dismissForSession: a device that cannot
            // update itself does not stop being that device because the warning
            // was inconvenient once, so it comes back on the next launch.
            Advisory.dismissForSession(item.id)
        })
        card.addView(row)
        return card
    }

    private fun button(activity: Activity, label: String, color: Int, onTap: () -> Unit) =
        TextView(activity).apply {
            val dp = { v: Int ->
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
                    activity.resources.displayMetrics).toInt()
            }
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat(); setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener { onTap() }
        }
}
