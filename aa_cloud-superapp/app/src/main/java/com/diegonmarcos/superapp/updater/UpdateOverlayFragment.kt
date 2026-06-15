package com.diegonmarcos.superapp.updater
import com.diegonmarcos.superapp.MainActivity

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
import com.diegonmarcos.superapp.updater.UpdateProgress

/**
 * Fullscreen overlay shown while the in-app updater is checking,
 * downloading, or installing. Driven by [UpdateProgress] state
 * transitions (subscribed by [MainActivity]).
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
        column.addView(titleView)
        column.addView(progressBar)
        column.addView(detailView)
        root.addView(column)

        applyState(UpdateProgress.state)
        return root
    }

    fun applyState(state: UpdateProgress.State) {
        when (state) {
            is UpdateProgress.State.Idle -> { /* about to dismiss */ }
            is UpdateProgress.State.CheckingManifest -> {
                titleView.text = "Checking for updates…"
                detailView.text = "Reading GHCR manifest"
                progressBar.isIndeterminate = true
            }
            is UpdateProgress.State.Downloading -> {
                titleView.text = "Downloading ${state.percent}%"
                detailView.text = "${state.bytes.toMib()} / ${state.total.toMib()} MiB"
                progressBar.isIndeterminate = state.total <= 0
                progressBar.progress = state.percent
            }
            is UpdateProgress.State.Installing -> {
                titleView.text = "Installing…"
                detailView.text = "Handing the APK to the system installer"
                progressBar.isIndeterminate = true
            }
            is UpdateProgress.State.Done -> {
                titleView.text = "Done"
                detailView.text = "Update complete"
                progressBar.isIndeterminate = false
                progressBar.progress = 100
            }
            is UpdateProgress.State.Failed -> {
                titleView.text = "Update failed"
                detailView.text = state.message
                progressBar.isIndeterminate = false
                progressBar.progress = 0
            }
        }
    }

    private fun Long.toMib(): String = "%.2f".format(this / (1024.0 * 1024.0))
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "update_overlay"
        fun newInstance() = UpdateOverlayFragment()
    }
}
