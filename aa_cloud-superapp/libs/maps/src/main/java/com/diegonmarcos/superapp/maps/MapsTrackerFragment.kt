package com.diegonmarcos.superapp.maps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Maps → Tracker page. The single user-facing surface for starting /
 * stopping the foreground GPS service + reviewing tracker health.
 *
 *   Status block       — green ●  Tracker running    OR
 *                        amber ●  Tracker idle        OR
 *                        red   ●  Permissions missing
 *   Counters           — # points stored · # stops detected · last fix.
 *   Permission state   — list of missing perms with one tap to request.
 *   Start / Stop       — the actual switch. Disabled while perms are
 *                        missing.
 *   Reset data         — wipes the local SQLite DB (points + stops +
 *                        places cache). Re-confirmed by a second tap
 *                        so a single accidental tap never nukes the
 *                        history.
 *
 * Provider + calibration knobs already live in Configs → Maps, so we
 * don't duplicate them here — this is purely the operational panel.
 */
class MapsTrackerFragment : Fragment() {

    private val PERM_REQUEST_CODE = 9991

    private var statusView: TextView? = null
    private var countsView: TextView? = null
    private var permsView: TextView? = null
    private var startBtn: Button? = null
    private var stopBtn: Button? = null
    private var resetBtn: Button? = null
    private var resetArmed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        root.addView(header(ctx, "Tracker"))
        statusView = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        }
        root.addView(statusView)
        root.addView(spacer(ctx, dp(ctx, 8)))

        countsView = TextView(ctx).apply {
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        }
        root.addView(countsView)
        root.addView(spacer(ctx, dp(ctx, 16)))

        permsView = TextView(ctx).apply {
            setTextColor(0xFFF59E0B.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        }
        root.addView(permsView)

        // Start / Stop buttons.
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val mt = dp(ctx, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = mt }
        }
        startBtn = Button(ctx).apply {
            text = "Start"
            setOnClickListener {
                MapsHaptics.tap(it)
                val missing = MapsPermissions.missing(ctx)
                if (missing.isNotEmpty()) {
                    MapsPermissions.request(requireActivity(), missing, PERM_REQUEST_CODE)
                } else {
                    LocationTrackerService.startTracking(ctx)
                    refresh()
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        stopBtn = Button(ctx).apply {
            text = "Stop"
            setOnClickListener {
                MapsHaptics.tap(it)
                LocationTrackerService.stopTracking(ctx)
                refresh()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 8)
            }
        }
        btnRow.addView(startBtn)
        btnRow.addView(stopBtn)
        root.addView(btnRow)

        // Danger zone — wipe DB. Armed on first tap, confirmed on second.
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(caption(ctx, "Data reset wipes every collected point + stop + cached place. Irreversible — exports first if you want a backup (Export coming in next push)."))
        resetBtn = Button(ctx).apply {
            text = "Reset all tracker data"
            setOnClickListener {
                MapsHaptics.tap(it)
                if (!resetArmed) {
                    resetArmed = true
                    text = "Tap again to confirm wipe"
                } else {
                    MapsDb.get(ctx).clearAll()
                    resetArmed = false
                    text = "Reset all tracker data"
                    refresh()
                }
            }
        }
        root.addView(resetBtn)

        refresh()
        return scroll
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Re-read all state and update labels + button enablement. Cheap
     *  enough to fire on every onResume / button-press. */
    private fun refresh() {
        val ctx = context ?: return
        val trackerPrefs = MapsTrackerPrefs(ctx)
        val db = MapsDb.get(ctx)
        val missing = MapsPermissions.missing(ctx)
        val running = trackerPrefs.enabled

        statusView?.text = when {
            missing.isNotEmpty() -> "● Permissions missing"
            running              -> "● Tracker running"
            else                 -> "● Tracker idle"
        }
        statusView?.setTextColor(when {
            missing.isNotEmpty() -> 0xFFEF4444.toInt()
            running              -> 0xFF22C55E.toInt()
            else                 -> 0xFFF59E0B.toInt()
        })

        val lastTs = trackerPrefs.lastFixTs
        val lastFixStr = if (lastTs <= 0L) "no fix yet" else
            "last fix " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(lastTs))
        countsView?.text =
            "Points: ${db.pointCount()}\n" +
            "Stops:  ${db.stopCount()}\n" +
            lastFixStr

        permsView?.text = if (missing.isEmpty()) ""
            else "Needs: " + missing.joinToString(", ") {
                it.substringAfterLast('.').replace('_', ' ').lowercase()
            } + "  ← tap Start to request"
        permsView?.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE

        startBtn?.isEnabled = !running
        stopBtn?.isEnabled  = running
    }

    private fun header(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun caption(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xAAFFFFFFL.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun spacer(ctx: android.content.Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
    private fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MapsTrackerFragment() }
}
