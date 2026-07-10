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

/**
 * Fullscreen overlay shown while the in-app updater is checking,
 * downloading, or installing. Driven by [UpdateProgress] state
 * transitions (subscribed by the host Activity).
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx).apply {
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
        column.addView(titleView)
        column.addView(progressBar)
        column.addView(detailView)
        column.addView(cancelButton)
        column.addView(dismissButton)
        root.addView(column)

        applyState(UpdateProgress.state)
        return root
    }

    fun applyState(state: UpdateProgress.State) {
        val terminal = state is UpdateProgress.State.Failed || state is UpdateProgress.State.Done
        val active = state is UpdateProgress.State.CheckingManifest ||
            state is UpdateProgress.State.Downloading || state is UpdateProgress.State.Installing
        dismissButton.visibility = if (terminal) View.VISIBLE else View.GONE
        cancelButton.visibility = if (active) View.VISIBLE else View.GONE
        progressBar.visibility = if (terminal) View.GONE else View.VISIBLE
        when (state) {
            is UpdateProgress.State.Idle -> { /* about to dismiss */ }
            is UpdateProgress.State.Cancelled -> { UpdateProgress.reset() }
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
                dismissButton.text = "OK"
            }
        }
    }

    private fun batch(title: String): String =
        UpdateProgress.batchLabel?.let { "$it\n$title" } ?: title

    private fun Long.toMib(): String = "%.2f".format(this / (1024.0 * 1024.0))
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "update_overlay"
        fun newInstance() = UpdateOverlayFragment()
    }
}
