package com.diegonmarcos.superapp.updater

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.core.NotificationStore

/**
 * Fullscreen overlay shown while the in-app updater is checking,
 * downloading, or installing. Driven by [UpdateProgress] state
 * transitions (subscribed by the host activity).
 *
 *   CheckingManifest → indeterminate spinner + "Checking…"
 *   Downloading      → percent bar + "Downloading 42%" + bytes/total
 *   Installing       → indeterminate spinner + "Installing…"
 *   Failed           → message + Dismiss
 */
class UpdateOverlayFragment : Fragment() {

    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var dismissButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var minimizeButton: TextView
    private lateinit var updateNowButton: TextView
    private lateinit var diagnoseButton: TextView
    private lateinit var actionRow: LinearLayout
    /** Report text once collected, so Copy/Save/Send all send the SAME bytes
     *  the user just read rather than re-collecting a slightly different one. */
    private var report: String? = null
    private var failed: UpdateProgress.State.Failed? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx).apply {
            // Solid dark scrim so the overlay is unambiguously the focus.
            setBackgroundColor(0xE6000000.toInt())
            isClickable = true; isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = dp(24); setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        titleView = TextView(ctx).apply {
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            text = ""
        }
        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8),
            ).apply { topMargin = dp(20); bottomMargin = dp(8) }
        }
        detailView = TextView(ctx).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            text = ""
        }
        // Dismiss control — shown ONLY on terminal states (Failed / Done) so
        // the user can read the error and hand controls back. During an active
        // check/download/install it stays hidden (the scrim blocks input on
        // purpose). Tapping it drives UpdateProgress → Idle, which makes
        // the host activity remove this overlay.
        dismissButton = TextView(ctx).apply {
            text = "OK"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF7C3AED.toInt())
            setPadding(dp(28), dp(12), dp(28), dp(12))
            isClickable = true; isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) }
            setOnClickListener { UpdateProgress.reset() }
        }
        // Cancel control — shown DURING an active check/download/install so a
        // stuck update can always be aborted (the reported "can't cancel when it
        // gets stuck" bug). Aborts the worker + flips state to Cancelled.
        cancelButton = TextView(ctx).apply {
            text = "Cancel"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4A4A55.toInt())
            setPadding(dp(28), dp(12), dp(28), dp(12))
            isClickable = true; isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
            setOnClickListener { runCatching { Updater.cancelNow(requireContext()) } }
        }
        // Minimize — "give me my screen back", NOT "stop". Shown beside Cancel
        // during any active state, because a manual update the user chose to
        // start is still not a reason to hold the whole screen hostage until it
        // finishes. It is deliberately a peer of Cancel and not a corner "x":
        // the two outcomes are different enough that the safe one has to be as
        // easy to hit as the destructive one.
        //
        // This touches UpdateProgress' VISIBILITY latch only. No cancelNow, no
        // reset, no state write — the download and install carry on untouched
        // and never learn this happened. The scrim goes with the fragment, so
        // input returns to the app underneath.
        minimizeButton = TextView(ctx).apply {
            text = "Minimize"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4A4A55.toInt())
            setPadding(dp(28), dp(12), dp(28), dp(12))
            isClickable = true; isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            setOnClickListener {
                runCatching {
                    val c = requireContext()
                    // Tell the user where the work went and how to get it back,
                    // on the feed they already have. Reusing NotificationStore
                    // rather than adding a status surface is the whole point —
                    // this complaint has produced four of those already.
                    NotificationStore.push(
                        ctx      = c,
                        source   = "Updater",
                        title    = "Update continues in the background",
                        body     = (UpdateProgress.batchLabel ?: "Update") +
                            " is still running. Tap Check for updates to bring the progress view back.",
                        severity = NotificationStore.Sev.INFO,
                    )
                    // Latch first, then drop the view: the host re-attaches on
                    // the next progress tick, so removing the fragment without
                    // setting the latch would simply bounce it back on screen.
                    UpdateProgress.minimize()
                    parentFragmentManager.beginTransaction()
                        .remove(this@UpdateOverlayFragment)
                        .commitAllowingStateLoss()
                }
            }
        }
        // "Update now" — shown ONLY on the metered UpdateAvailable prompt. Grants
        // consent to download over mobile data; kicks a forced one-shot check.
        updateNowButton = TextView(ctx).apply {
            text = "Update now"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF7C3AED.toInt())
            setPadding(dp(28), dp(12), dp(28), dp(12))
            isClickable = true; isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
            setOnClickListener { runCatching { Updater.downloadNow(requireContext()) } }
        }
        column.addView(titleView)
        column.addView(progressBar)
        column.addView(detailView)
        // Diagnose — offered only on Failed. "Update failed: <platform string>"
        // is where every one of these ends, and the platform string names a
        // symptom, not a cause. This turns the dead end into the local state
        // that explains it.
        diagnoseButton = TextView(ctx).apply {
            text = "Diagnose"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2A2A33.toInt())
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isClickable = true; isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            setOnClickListener { showReport(ctx) }
        }
        // Copy / Save / Send, revealed with the report. Three because the phone
        // that cannot reach the network is exactly the phone you most need the
        // report off, so Send must never be the only way out.
        actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        fun action(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3A3A45.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
            setOnClickListener { onTap() }
        }
        actionRow.addView(action("Copy") { copyReport(ctx) })
        actionRow.addView(action("Save") { saveReport(ctx) })
        if (com.diegonmarcos.superapp.devtools.DiagnosticsPush.canReport()) {
            actionRow.addView(action("Send") { sendReport(ctx) })
        }
        column.addView(diagnoseButton)
        column.addView(actionRow)
        column.addView(cancelButton)
        column.addView(minimizeButton)
        column.addView(updateNowButton)
        column.addView(dismissButton)
        root.addView(column)

        applyState(UpdateProgress.state)
        return root
    }

    fun applyState(state: UpdateProgress.State) {
        // Terminal states (Failed / Done) get the OK button + hidden bar;
        // in-progress states show the bar and hide the button.
        val terminal = state is UpdateProgress.State.Failed || state is UpdateProgress.State.Done
        val active = state is UpdateProgress.State.CheckingManifest ||
            state is UpdateProgress.State.Downloading || state is UpdateProgress.State.Installing
        // Metered "ask before download" prompt: Update now (primary) + Later
        // (the reused dismiss button, which resets → dismisses the overlay).
        val prompt = state is UpdateProgress.State.UpdateAvailable
        dismissButton.visibility = if (terminal || prompt) View.VISIBLE else View.GONE
        cancelButton.visibility = if (active) View.VISIBLE else View.GONE
        // Exactly when Cancel shows: while there is something running to walk
        // away from. On a terminal state there is nothing to minimize — OK
        // already dismisses.
        minimizeButton.visibility = if (active) View.VISIBLE else View.GONE
        updateNowButton.visibility = if (prompt) View.VISIBLE else View.GONE
        progressBar.visibility = if (terminal || prompt) View.GONE else View.VISIBLE
        when (state) {
            is UpdateProgress.State.Idle -> { /* about to dismiss */ }
            is UpdateProgress.State.Cancelled -> { UpdateProgress.reset() /* dismiss */ }
            is UpdateProgress.State.UpdateAvailable -> {
                titleView.text = "Update available"
                detailView.text = "${state.totalBytes.toMib()} MiB · you're on mobile data. Download now?"
                dismissButton.text = "Later"
            }
            is UpdateProgress.State.CheckingManifest -> {
                titleView.text = batch("Checking for updates…")
                detailView.text = "Reading GHCR manifest"
                progressBar.isIndeterminate = true
            }
            is UpdateProgress.State.Downloading -> {
                titleView.text = batch("Downloading ${state.percent}%")
                detailView.text = "${state.bytes.toMib()} / ${state.total.toMib()} MiB"
                progressBar.isIndeterminate = state.total <= 0
                progressBar.progress = state.percent
            }
            is UpdateProgress.State.Installing -> {
                titleView.text = batch("Installing…")
                detailView.text = "Handing the APK to the system installer"
                progressBar.isIndeterminate = true
            }
            is UpdateProgress.State.Done -> {
                titleView.text = "Done"
                detailView.text = "Update complete"
                dismissButton.text = "OK"
            }
            is UpdateProgress.State.Failed -> {
                titleView.text = "Update failed"
                detailView.text = state.message
                failed = state
                diagnoseButton.visibility = View.VISIBLE
                dismissButton.text = "OK"
            }
        }
    }

    /** Prefix an active-state title with the "Chat · 2/5" batch header when a
     *  multi-app "Update all" is running; plain title for single installs. */
    private fun batch(title: String): String =
        UpdateProgress.batchLabel?.let { "$it\n$title" } ?: title

    private fun Long.toMib(): String = "%.2f".format(this / (1024.0 * 1024.0))
    /** Collect once, show it, and swap the buttons for the export row. */
    private fun showReport(ctx: android.content.Context) {
        val f = failed
        val text = report ?: InstallDiagnostics.collect(
            ctx,
            appId = f?.appId?.ifBlank { ctx.packageName } ?: ctx.packageName,
            pkg = f?.pkg?.ifBlank { ctx.packageName } ?: ctx.packageName,
            error = f?.message ?: "unknown",
            apk = f?.apkPath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) },
        ).also { report = it }
        titleView.text = "Diagnosis"
        detailView.text = text
        // The report is long; let it scroll in place rather than opening a
        // second screen the user has to find their way back from.
        detailView.maxLines = 18
        detailView.movementMethod = android.text.method.ScrollingMovementMethod()
        detailView.setTextIsSelectable(true)
        detailView.setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        detailView.typeface = Typeface.MONOSPACE
        diagnoseButton.visibility = View.GONE
        actionRow.visibility = View.VISIBLE
    }

    private fun copyReport(ctx: android.content.Context) {
        val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        cb?.setPrimaryClip(android.content.ClipData.newPlainText("diagnosis", report.orEmpty()))
        toast(ctx, "Copied — paste it anywhere")
    }

    private fun saveReport(ctx: android.content.Context) {
        val name = "install-diagnosis-${System.currentTimeMillis()}.txt"
        val path = com.diegonmarcos.superapp.devtools.DiagnosticsPush
            .downloadBundle(ctx, name, report.orEmpty())
        toast(ctx, if (path != null) "Saved to Downloads/$name" else "Could not write to Downloads")
    }

    /** Off the main thread: this is a network POST behind a button someone
     *  just tapped, and blocking the UI thread on it would ANR the overlay. */
    private fun sendReport(ctx: android.content.Context) {
        val app = failed?.appId?.ifBlank { ctx.packageName } ?: ctx.packageName
        val body = report.orEmpty()
        toast(ctx, "Sending…")
        Thread {
            val code = com.diegonmarcos.superapp.devtools.DiagnosticsPush.postReport(app, body)
            view?.post {
                toast(ctx, if (code in 200..299) "Sent as $app" else "Send failed (HTTP $code) — Copy or Save instead")
            }
        }.start()
    }

    private fun toast(ctx: android.content.Context, m: String) =
        android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "update_overlay"
        fun newInstance() = UpdateOverlayFragment()
    }
}
