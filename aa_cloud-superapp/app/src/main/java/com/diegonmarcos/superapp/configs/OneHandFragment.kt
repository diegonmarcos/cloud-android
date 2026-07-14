package com.diegonmarcos.superapp.configs

import android.os.Bundle
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
 * Configs > One-Hand. Master toggle + a per-swipe action editor: for every
 * handle and every swipe slot, a dropdown of actions (global + launch-app).
 * Grant the two permissions in the centralized Configs > Permissions page.
 */
class OneHandFragment : Fragment() {

    private lateinit var status: TextView
    private lateinit var toggle: Switch

    // Option = a picker row. null action = "None".
    private data class Option(val label: String, val action: GestureAction?)

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(ctx)
        col.addView(status)

        toggle = Switch(ctx).apply {
            text = "One-Hand edge handles"
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (OneHandController.ready(ctx)) OneHandController.enable(ctx)
                    else { isChecked = false; Toast.makeText(ctx,
                        "Grant 'Display over apps' + Accessibility in Configs › Permissions",
                        Toast.LENGTH_LONG).show() }
                } else OneHandController.disable(ctx)
            }
        }
        col.addView(toggle)

        val cfg = OneHandConfig.effective(ctx)
        val options = buildOptions(cfg)
        cfg.handles.forEach { h -> addHandleEditor(col, ctx, h, options, pad) }

        return ScrollView(ctx).apply { addView(col) }
    }

    private fun buildOptions(cfg: OneHandConfig): List<Option> = buildList {
        add(Option("None", null))
        OneHandAction.entries
            .filter { it != OneHandAction.NONE && it.supported }
            .forEach { add(Option(prettify(it.name), GestureAction.Global(it))) }
        cfg.apps.forEach { add(Option("Open: ${it.label}", GestureAction.OpenApp(it.pkg))) }
    }

    private fun addHandleEditor(
        col: LinearLayout, ctx: android.content.Context,
        h: OneHandConfig.Handle, options: List<Option>, pad: Int,
    ) {
        col.addView(TextView(ctx).apply {
            text = "${h.edge.name.lowercase().replaceFirstChar { it.uppercase() }} handle"
            setPadding(0, pad, 0, pad / 2)
            textSize = 16f
        })
        val labels = options.map { it.label }
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)

        for (slot in OneHandConfig.slotsFor(h.edge)) {
            col.addView(TextView(ctx).apply { text = slot.label; setPadding(0, pad / 2, 0, 0) })
            val current = h.gestures[slot.key]
            val sel = options.indexOfFirst { it.action?.serialize() == current?.serialize() }
                .coerceAtLeast(0)
            col.addView(Spinner(ctx).apply {
                this.adapter = adapter
                setSelection(sel)
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

    companion object {
        fun newInstance() = OneHandFragment()
    }
}
