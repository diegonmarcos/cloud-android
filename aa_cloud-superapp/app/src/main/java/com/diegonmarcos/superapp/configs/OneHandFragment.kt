package com.diegonmarcos.superapp.configs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.onehand.OneHandController

/**
 * Configs > One-Hand. Master toggle for the edge-gesture overlay plus the two
 * special-permission deep-links. All engine logic lives in libs:onehand
 * (OneHandController) — this fragment is just the R-bound UI shell.
 */
class OneHandFragment : Fragment() {

    private lateinit var status: TextView
    private lateinit var toggle: Switch

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(ctx)
        col.addView(status)

        toggle = Switch(ctx).apply {
            text = "One-Hand edge handle"
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (OneHandController.ready(ctx)) OneHandController.enable(ctx)
                    else { isChecked = false; Toast.makeText(ctx,
                        "Grant both permissions below first", Toast.LENGTH_SHORT).show() }
                } else OneHandController.disable(ctx)
            }
        }
        col.addView(toggle)

        col.addView(Button(ctx).apply {
            text = "Grant: Draw over other apps"
            setOnClickListener { startActivity(OneHandController.overlaySettingsIntent(ctx)) }
        })
        col.addView(Button(ctx).apply {
            text = "Enable accessibility service"
            setOnClickListener { startActivity(OneHandController.accessibilitySettingsIntent()) }
        })

        return ScrollView(ctx).apply { addView(col) }
    }

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        val overlay = if (OneHandController.canDrawOverlay(ctx)) "✓" else "✗"
        val a11y = if (OneHandController.accessibilityEnabled()) "✓" else "✗"
        status.text = "Draw over other apps: $overlay\nAccessibility service: $a11y"
        // ponytail: switch is not persisted across reboot — the overlay service
        // isn't boot-started either; add a BootReceiver + pref if that's wanted.
    }

    companion object {
        fun newInstance() = OneHandFragment()
    }
}
