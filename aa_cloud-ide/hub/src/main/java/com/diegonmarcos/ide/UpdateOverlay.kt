package com.diegonmarcos.ide

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.diegonmarcos.ide.update.UpdateProgress

/**
 * Fullscreen update overlay — the Cloud-IDE equivalent of ea_cloud-superapp's
 * UpdateOverlayFragment. Dark scrim + headline + horizontal progress bar +
 * detail line, driven live by UpdateProgress state transitions. Terminal states
 * (UpToDate / Done / Failed) show a Dismiss button and auto-close.
 *
 *   CheckingManifest → indeterminate + "Checking for updates…"
 *   Downloading      → percent bar + "Downloading 42%" + MiB
 *   Installing       → indeterminate + "Installing…"
 *   UpToDate         → "Up to date" + auto-dismiss
 *   Done             → "Done"
 *   Failed           → message + Dismiss
 */
class UpdateOverlay : DialogFragment() {

    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var dismissBtn: Button
    private val main = Handler(Looper.getMainLooper())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0xE6000000.toInt()))

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = dp(24); setPadding(p, p, p, p)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        titleView = TextView(ctx).apply {
            setTextColor(0xFFE9D8FD.toInt()); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        }
        bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; max = 100
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(8)).apply {
                topMargin = dp(20); bottomMargin = dp(8)
            }
        }
        detailView = TextView(ctx).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 14f }
        dismissBtn = Button(ctx).apply {
            text = "Dismiss"; visibility = android.view.View.GONE
            setOnClickListener { dismissAllowingStateLoss() }
        }
        column.addView(titleView); column.addView(bar); column.addView(detailView); column.addView(dismissBtn)
        dialog.setContentView(column)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(MATCH, MATCH)
        UpdateProgress.setListener { state -> main.post { applyState(state) } }
    }

    override fun onStop() {
        super.onStop()
        UpdateProgress.setListener(null)
    }

    private fun applyState(state: UpdateProgress.State) {
        when (state) {
            is UpdateProgress.State.Idle -> {}
            is UpdateProgress.State.CheckingManifest -> {
                titleView.text = "Checking for updates…"; detailView.text = "Reading GHCR manifest"
                bar.isIndeterminate = true; dismissBtn.visibility = android.view.View.GONE
            }
            is UpdateProgress.State.Downloading -> {
                titleView.text = "Downloading ${state.percent}%"
                detailView.text = "${state.bytes.toMib()} / ${state.total.toMib()} MiB"
                bar.isIndeterminate = state.total <= 0; bar.progress = state.percent
            }
            is UpdateProgress.State.Installing -> {
                titleView.text = "Installing…"; detailView.text = "Handing the APK to the system installer"
                bar.isIndeterminate = true
            }
            is UpdateProgress.State.UpToDate -> {
                titleView.text = "Up to date"; detailView.text = "Latest: ${state.tag}"
                bar.isIndeterminate = false; bar.progress = 100
                autoClose()
            }
            is UpdateProgress.State.Done -> {
                titleView.text = "Done"; detailView.text = "Update complete"
                bar.isIndeterminate = false; bar.progress = 100; autoClose()
            }
            is UpdateProgress.State.Failed -> {
                titleView.text = "Update failed"; detailView.text = state.message
                bar.isIndeterminate = false; bar.progress = 0
                dismissBtn.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun autoClose() {
        dismissBtn.visibility = android.view.View.VISIBLE
        main.postDelayed({ if (isAdded) dismissAllowingStateLoss() }, 1800)
    }

    private fun Long.toMib(): String = "%.2f".format(this / (1024.0 * 1024.0))
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "update_overlay"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        fun newInstance() = UpdateOverlay()
    }
}
