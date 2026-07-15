package com.diegonmarcos.superapp.configs

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.onehand.GestureAction
import com.diegonmarcos.superapp.onehand.OneHandAction
import com.diegonmarcos.superapp.onehand.OneHandConfig
import com.diegonmarcos.superapp.onehand.OneHandController
import com.diegonmarcos.superapp.onehand.OneHandPrefs

/**
 * Configs > One-Hand. Master toggle + a per-sector action editor laid out to
 * MIRROR the phone: the left handle's Top/Center/Down live in the LEFT column,
 * the right handle's in the RIGHT column. Grant the two permissions in the
 * centralized Configs > Permissions page.
 */
class OneHandFragment : Fragment() {

    private lateinit var status: TextView
    private lateinit var toggle: Switch

    private data class Option(
        val label: String, val action: GestureAction?,
        val icon: android.graphics.drawable.Drawable? = null,
    )

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        }

        status = TextView(ctx)
        root.addView(status)

        toggle = Switch(ctx).apply {
            text = "One-Hand edge handles"; setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (OneHandController.ready(ctx)) OneHandController.enable(ctx)
                    else { isChecked = false; Toast.makeText(ctx,
                        "Grant 'Display over apps' + Accessibility in Configs › Permissions",
                        Toast.LENGTH_LONG).show() }
                } else OneHandController.disable(ctx)
            }
        }
        root.addView(toggle)

        root.addView(Switch(ctx).apply {
            text = "Summon menu on long-press (off = on touch)"
            isChecked = OneHandConfig.effective(ctx).trigger == OneHandConfig.Trigger.LONG_PRESS
            setOnCheckedChangeListener { _, on ->
                OneHandPrefs.setTrigger(ctx,
                    if (on) OneHandConfig.Trigger.LONG_PRESS else OneHandConfig.Trigger.TOUCH)
                OneHandController.refresh(ctx)
            }
        })

        val cfg = OneHandConfig.effective(ctx)
        val options = buildOptions(cfg)

        // Two mirrored columns: left-edge handles on the left, right on the right.
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val leftCol = column(ctx); val rightCol = column(ctx)
        row.addView(leftCol); row.addView(rightCol)
        root.addView(row)

        cfg.handles.forEach { h ->
            val target = if (h.edge == OneHandConfig.Edge.RIGHT) rightCol else leftCol
            addHandleEditor(target, ctx, h, options, pad)
        }

        return ScrollView(ctx).apply { addView(root) }
    }

    private fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun buildOptions(cfg: OneHandConfig): List<Option> = buildList {
        add(Option("None", null))
        OneHandAction.entries
            .filter { it != OneHandAction.NONE && it.supported }
            .forEach { add(Option(prettify(it.name), GestureAction.Global(it))) }
        val pm = requireContext().packageManager
        cfg.apps.forEach {
            val icon = runCatching { pm.getApplicationIcon(it.pkg) }.getOrNull()
            add(Option(it.label, GestureAction.OpenApp(it.pkg), icon))
        }
    }

    private fun optionAdapter(ctx: Context, options: List<Option>) =
        object : ArrayAdapter<Option>(ctx, 0, options) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup) = rowView(ctx, options[pos])
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup) = rowView(ctx, options[pos])
        }

    /** One spinner row: app icon (if any) + label. */
    private fun rowView(ctx: Context, o: Option): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            o.icon?.let {
                addView(android.widget.ImageView(ctx).apply {
                    setImageDrawable(it)
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
                })
            }
            addView(TextView(ctx).apply { text = o.label })
        }
    }

    private fun addHandleEditor(
        col: LinearLayout, ctx: Context, h: OneHandConfig.Handle,
        options: List<Option>, pad: Int,
    ) {
        col.addView(TextView(ctx).apply {
            text = "${h.edge.name.lowercase().replaceFirstChar { it.uppercase() }} handle"
            gravity = Gravity.CENTER; setPadding(0, pad, 0, pad / 2); textSize = 16f
        })
        val adapter = optionAdapter(ctx, options)
        for (slot in OneHandConfig.slotsFor(h.edge)) {
            col.addView(TextView(ctx).apply {
                text = slot.label; gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, 0)
            })
            val current = h.gestures[slot.key]
            val sel = options.indexOfFirst { it.action?.serialize() == current?.serialize() }
                .coerceAtLeast(0)
            col.addView(Spinner(ctx).apply {
                this.adapter = adapter; setSelection(sel)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        OneHandPrefs.setAction(ctx, h.id, slot.key, options[pos].action)
                        OneHandController.refresh(ctx)
                    }
                }
            })
        }
    }

    private fun prettify(name: String) =
        name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        val overlay = if (OneHandController.canDrawOverlay(ctx)) "✓" else "✗"
        val a11y = if (OneHandController.accessibilityEnabled()) "✓" else "✗"
        status.text = "Requires (grant in Configs › Permissions):\n" +
            "Display over apps: $overlay\nAccessibility service: $a11y"
        toggle.isChecked = OneHandController.isOn(ctx)
    }

    companion object { fun newInstance() = OneHandFragment() }
}
