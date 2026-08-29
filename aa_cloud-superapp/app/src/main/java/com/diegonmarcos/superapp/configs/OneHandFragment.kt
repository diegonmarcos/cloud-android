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
import com.diegonmarcos.superapp.settings.HomeSwipePrefs

/**
 * Configs > One-Hand. Master toggle + a per-sector action editor laid out to
 * MIRROR the phone: the left handle's Top/Center/Down live in the LEFT column,
 * the right handle's in the RIGHT column. Grant the two permissions in the
 * centralized Configs > Permissions page.
 *
 * This module also owns the THREE home-screen stars (see MainActivity /
 * libs:launcher-onehand — not editable from this screen, this is descriptive only):
 *   - Sirius   — full multi-level circular-menu (Suite/Infos/Labs/Configs…)
 *   - Canopus  — single-level arc-menu over the Configs section's pages
 *   - Centauri — SAME arc-menu as Canopus, showing the last 9 recently-opened
 *                Android apps (needs Usage-access, Configs > Permissions)
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

        // Descriptive only — the 3 stars are wired in MainActivity, not toggled
        // from this screen. Glyph/size shared via onehand.circular_menu.star;
        // Centauri additionally needs Usage-access (Configs > Permissions).
        root.addView(TextView(ctx).apply {
            text = "Home screen has 3 stars: ✦ Sirius (full menu) · " +
                "✦ Canopus (Configs arc) · ✦ Centauri (recent apps arc)"
            setTextColor(0x99FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, pad / 4, 0, pad / 2)
        })

        // How the menu is summoned. Swipe (Samsung edge-panel style) is the default:
        // touch the edge handle + drag inward. A plain tap passes through to the app.
        root.addView(android.widget.TextView(ctx).apply { text = "Activation" })
        val triggers = listOf(
            OneHandConfig.Trigger.SWIPE to "Hold & swipe in (Samsung edge style)",
            OneHandConfig.Trigger.LONG_PRESS to "Long-press the handle",
            OneHandConfig.Trigger.TOUCH to "Touch the handle",
        )
        root.addView(android.widget.RadioGroup(ctx).apply {
            val current = OneHandConfig.effective(ctx).trigger
            triggers.forEach { (t, label) ->
                addView(android.widget.RadioButton(ctx).apply {
                    text = label; id = View.generateViewId(); isChecked = t == current
                    setOnClickListener { OneHandPrefs.setTrigger(ctx, t); OneHandController.refresh(ctx) }
                })
            }
        })

        root.addView(Switch(ctx).apply {
            text = "Show handles (debug — bright red bars)"
            isChecked = OneHandPrefs.debugVisible(ctx)
            setOnCheckedChangeListener { _, on ->
                OneHandPrefs.setDebugVisible(ctx, on)
                OneHandController.refresh(ctx)  // rebuild handles with new visibility
            }
        })

        root.addView(android.widget.Button(ctx).apply {
            text = "Activate 30s (test)"
            setOnClickListener {
                val ok = OneHandController.testActivate(ctx, 30_000L)
                Toast.makeText(ctx,
                    if (ok) "One-Hand ON for 30s, then auto-off"
                    else "Grant Display-over-apps + Accessibility first (Configs › Permissions)",
                    Toast.LENGTH_LONG).show()
            }
        })

        // Home-screen swipe actions — runtime-editable overrides of
        // build.json::onehand.home_swipes. These fire ONLY while on the Home
        // section (MainActivity guards on currentSection == "home"); off-home
        // the inner fragment owns the gesture.
        root.addView(TextView(ctx).apply {
            text = "Home-screen swipes"
            gravity = Gravity.CENTER; setPadding(0, pad, 0, pad / 2); textSize = 16f
        })
        addHomeSwipePicker(root, ctx, "Swipe up",    { HomeSwipePrefs(ctx).up },    { HomeSwipePrefs(ctx).up = it })
        addHomeSwipePicker(root, ctx, "Swipe left",  { HomeSwipePrefs(ctx).left },  { HomeSwipePrefs(ctx).left = it })
        addHomeSwipePicker(root, ctx, "Swipe right", { HomeSwipePrefs(ctx).right }, { HomeSwipePrefs(ctx).right = it })
        addHomeSwipePicker(root, ctx, "Swipe down",  { HomeSwipePrefs(ctx).down },  { HomeSwipePrefs(ctx).down = it })

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
        // Config / global actions.
        OneHandAction.entries
            .filter { it != OneHandAction.NONE && it.supported }
            .forEach { add(Option(prettify(it.name), GestureAction.Global(it))) }
        val pm = requireContext().packageManager
        val seen = HashSet<String>()
        // Curated favourites first (from build.json), with their nice labels.
        cfg.apps.forEach {
            if (seen.add(it.pkg)) {
                val icon = runCatching { pm.getApplicationIcon(it.pkg) }.getOrNull()
                add(Option("★ ${it.label}", GestureAction.OpenApp(it.pkg), icon))
            }
        }
        // Then EVERY installed launchable app, alphabetical.
        val li = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(li, 0)
            .mapNotNull { ri -> ri.activityInfo?.packageName?.let { Triple(it, ri.loadLabel(pm).toString(), ri) } }
            .filter { it.first !in seen }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .forEach { (pkg, label, ri) ->
                seen.add(pkg)
                add(Option(label, GestureAction.OpenApp(pkg), ri.loadIcon(pm)))
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

    /** One home-swipe row: a caption + a Spinner over the shared action
     *  vocabulary. Reads the current value via [get], persists via [set]. */
    private fun addHomeSwipePicker(
        col: LinearLayout, ctx: Context, label: String,
        get: () -> String, set: (String) -> Unit,
    ) {
        val d = resources.displayMetrics.density
        col.addView(TextView(ctx).apply {
            text = label; setPadding(0, (8 * d).toInt(), 0, 0)
        })
        val actions = HomeSwipePrefs.ACTIONS
        val adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item, actions.map { it.second })
        val cur = actions.indexOfFirst { it.first == get() }.coerceAtLeast(0)
        col.addView(Spinner(ctx).apply {
            this.adapter = adapter; setSelection(cur)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: AdapterView<*>?) {}
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    set(actions[pos].first)
                }
            }
        })
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
