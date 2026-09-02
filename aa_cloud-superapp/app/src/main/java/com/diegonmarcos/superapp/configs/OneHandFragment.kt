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
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.onehand.ArcMenu
import com.diegonmarcos.superapp.onehand.CircularMenu
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
        val pad = dp(16)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        }

        root.addView(title(ctx, "One-Hand"))
        root.addView(caption(ctx,
            "Everything reachable with the thumb alone: the three Home-screen " +
            "stars, the edge menu that works on top of any app, and the four " +
            "Home swipes. Content and defaults are data-driven from " +
            "build.json::onehand — the switches and pickers below are runtime " +
            "overrides kept in the onehand_prefs store, and each one says which " +
            "it is."))

        section(root, ctx, "Home-Screen Stars")
        addStars(root, ctx)

        section(root, ctx, "Edge Menu")
        addEdgeMenu(root, ctx, pad)

        section(root, ctx, "Home-Screen Gestures")
        addHomeGestures(root, ctx)

        return ScrollView(ctx).apply { addView(root) }
    }

    // ────────────────────────────── 1. Stars ──────────────────────────────

    /**
     * Descriptive only — the stars are wired in MainActivity and have no on/off
     * switch here. Every card is read from the SAME config the star itself
     * reads, so this page cannot drift from what the star draws.
     */
    private fun addStars(root: LinearLayout, ctx: Context) {
        val cm = CircularMenu.config()

        root.addView(caption(ctx,
            "Three stars sit on the Home screen, one under the other: Sirius at " +
            "the centre, Centauri midway down, Canopus just above the bottom-nav " +
            "island. Touching one opens its menu; they are always on."))

        // The one distinction the whole design rests on. Everything a star can
        // show is either a place you GO or a thing you DO, and the ring it
        // lands on is what tells them apart.
        root.addView(subhead(ctx, "Two rings, two meanings"))
        root.addView(caption(ctx,
            "OUTER full circle — APPS / PAGES. Where you GO. A branch redraws " +
            "the ring one level deeper, so the outer ring is a tree you walk " +
            "with the thumb; a leaf navigates and closes. Nothing on this ring " +
            "changes anything.\n\n" +
            "INNER half circle — ACTIONS. What you DO. One tap fires it and the " +
            "menu closes. An action never has children and never navigates, " +
            "which is why it sits apart on its own smaller disc, " +
            "${cm.ringGapDp}dp inside the outer ring — the gap IS the meaning.\n\n" +
            "Same two rings on all three stars. Only the source of each ring " +
            "changes, which is the whole difference between them:"))

        val nodes = cm.nodes
        starCard(ctx, root, "✦ Sirius — all apps",
            "screen centre · radius ${cm.radiusDp}dp",
            "The full section tree. " +
                "${nodes.count { it.childKey != null }} of its ${nodes.size} entries " +
                "open a deeper ring, the rest navigate straight to a page.",
            nodes.map { it.label },
            cm.actions.map { it.label })

        starCard(ctx, root, "✦ Centauri — recent apps",
            "midway to the island · shares Canopus's half-moon fan",
            "The last 9 Android apps you opened, newest first, with their real " +
                "launcher icons — a live list from the system, not build.json. " +
                "Needs Usage access (Configs › Permissions); without it the ring " +
                "comes back empty.",
            listOf("(the 9 most recent apps, live)"),
            CircularMenu.actionsOf("recents_menu").map { it.label })

        val arc = ArcMenu.config()
        starCard(ctx, root, "✦ Canopus — configs",
            "above the bottom-nav island · radius ${arc.radiusDp}dp",
            "The pages of the \"${arc.section}\" section, so the settings you " +
                "reach for most are one thumb-touch from Home instead of two " +
                "taps deep.",
            SectionPages.pagesFor(arc.section).map { it.label },
            CircularMenu.actionsOf("arc_menu").map { it.label })

        root.addView(caption(ctx,
            "Glyph ${cm.starGlyph} and size ${cm.starSizeSp}sp are shared by all " +
            "three (onehand.circular_menu.star), so they always match. Editing " +
            "any ring means editing build.json and shipping an APK — these are " +
            "baked at build time, not runtime settings."))
    }

    /** One star: name, where it sits, and its two rings spelled out. */
    private fun starCard(
        ctx: Context, root: LinearLayout, name: String, place: String,
        what: String, outer: List<String>, inner: List<String>,
    ) {
        root.addView(subhead(ctx, name))
        root.addView(caption(ctx, place))
        root.addView(caption(ctx, what))
        root.addView(caption(ctx, "  ◯ Outer — apps/pages (${outer.size}): " +
            if (outer.isEmpty()) "—" else outer.joinToString(" · ")))
        root.addView(caption(ctx, "  ◑ Inner — actions (${inner.size}): " +
            if (inner.isEmpty()) "none" else inner.joinToString(" · ")))
    }

    // ───────────────────────────── 2. Edge menu ─────────────────────────────

    private fun addEdgeMenu(root: LinearLayout, ctx: Context, pad: Int) {
        val cfg = OneHandConfig.effective(ctx)

        root.addView(caption(ctx,
            "Two handles pinned to the screen edges, live on top of whatever app " +
            "is in front. Each handle answers several gestures, and every gesture " +
            "can be pointed at an action or at any installed app. Unlike the " +
            "stars this needs two system permissions, so it can be off."))

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

        // How the menu is summoned. Swipe (Samsung edge-panel style) is the default:
        // touch the edge handle + drag inward. A plain tap passes through to the app.
        root.addView(subhead(ctx, "Activation"))
        val triggers = listOf(
            OneHandConfig.Trigger.SWIPE to "Hold & swipe in (Samsung edge style)",
            OneHandConfig.Trigger.LONG_PRESS to "Long-press the handle",
            OneHandConfig.Trigger.TOUCH to "Touch the handle",
        )
        root.addView(android.widget.RadioGroup(ctx).apply {
            val current = cfg.trigger
            triggers.forEach { (t, label) ->
                addView(android.widget.RadioButton(ctx).apply {
                    text = label; id = View.generateViewId(); isChecked = t == current
                    setOnClickListener { OneHandPrefs.setTrigger(ctx, t); OneHandController.refresh(ctx) }
                })
            }
        })
        root.addView(caption(ctx,
            "Saved as a runtime override. Swipe-in needs ${cfg.swipeThresholdDp}dp " +
            "of inward travel before it counts; long-press needs " +
            "${cfg.longPressMs}ms. Both come from onehand.defaults — under them " +
            "the touch passes through to the app underneath, which is what keeps " +
            "the handles from stealing edge swipes."))

        // Two mirrored columns: left-edge handles on the left, right on the right.
        root.addView(subhead(ctx, "Handle gestures"))
        root.addView(caption(ctx,
            "One block per handle, mirrored to the edge it lives on. Every row " +
            "is a runtime override; 'None' clears it back to the build.json " +
            "default. The app list holds your build.json favourites (★) first, " +
            "then every launchable app on the phone."))
        val options = buildOptions(cfg)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val leftCol = column(ctx); val rightCol = column(ctx)
        row.addView(leftCol); row.addView(rightCol)
        root.addView(row)
        cfg.handles.forEach { h ->
            val target = if (h.edge == OneHandConfig.Edge.RIGHT) rightCol else leftCol
            addHandleEditor(target, ctx, h, options, pad)
        }

        // Full disclosure: the geometry is real and it is NOT editable here.
        // Saying so beats letting someone hunt for a slider that was never built.
        root.addView(subhead(ctx, "Handle placement (build-time)"))
        root.addView(caption(ctx, cfg.handles.joinToString("\n") { h ->
            val len = if (h.lengthDp > 0) "${h.lengthDp}dp" else "${h.lengthPct}% of the edge"
            "  ${h.id}: ${h.edge.name.lowercase()} edge · ${h.positionPct}% down · " +
                "$len long · ${h.thicknessDp}dp thick · ${h.transparency}% transparent" +
                (if (h.edgeInsetDp > 0) " · inset ${h.edgeInsetDp}dp" else "")
        }))
        root.addView(caption(ctx,
            "Baked in onehand.handles — no runtime override exists, so changing " +
            "these means editing build.json. The inset is deliberate on curved " +
            "screens: the border pixels sit on the curve where touch is " +
            "unreliable. Gesture-nav inset: ${cfg.edgeInsetGestureDp}dp."))

        // A whole feature the page never mentioned. Off by default, and with no
        // switch anywhere, so without this block it is invisible.
        root.addView(subhead(ctx, "Two-finger radial menu"))
        val r = cfg.radial
        root.addView(caption(ctx,
            if (r.enabled)
                "ON. Hold two fingers still for ${r.holdMs}ms anywhere " +
                "(within ${r.slopDp}dp of drift) and a ${r.radiusDp}dp ring of " +
                "${r.items.size} shortcuts opens under them. Independent of the " +
                "edge handles — it has no handle to hit."
            else
                "OFF. A separate summon: two fingers held still for " +
                "${r.holdMs}ms anywhere on screen open a ${r.radiusDp}dp " +
                "ring of ${r.items.size} shortcuts, no handle needed. It has no " +
                "runtime switch — enable onehand.radial.enabled in build.json. Needs Android 14+; the detector is observe-only below that."))

        root.addView(subhead(ctx, "Troubleshooting"))
        root.addView(Switch(ctx).apply {
            text = "Show handles (debug — bright red bars)"
            isChecked = OneHandPrefs.debugVisible(ctx)
            setOnCheckedChangeListener { _, on ->
                OneHandPrefs.setDebugVisible(ctx, on)
                OneHandController.refresh(ctx)  // rebuild handles with new visibility
            }
        })
        root.addView(caption(ctx,
            "The handles are invisible by default — paint them red to find out " +
            "where they actually are before blaming a gesture."))
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
        root.addView(caption(ctx,
            "Turns everything on for 30 seconds and off again, so a permission " +
            "check does not leave the handles enabled."))
    }

    // ─────────────────────────── 3. Home gestures ───────────────────────────

    private fun addHomeGestures(root: LinearLayout, ctx: Context) {
        // Runtime-editable overrides of build.json::onehand.home_swipes. These
        // fire ONLY while on the Home section (MainActivity guards on
        // currentSection == "home"); off-home the inner fragment owns the gesture.
        root.addView(caption(ctx,
            "Swipes on the Home screen itself — no handle, no permission, no " +
            "star. They fire ONLY while Home is the current section: open any " +
            "other section and the page you are on owns the gesture again, so " +
            "these can never eat a list scroll or a map pan."))
        addHomeSwipePicker(root, ctx, "Swipe up",    { HomeSwipePrefs(ctx).up },    { HomeSwipePrefs(ctx).up = it })
        addHomeSwipePicker(root, ctx, "Swipe left",  { HomeSwipePrefs(ctx).left },  { HomeSwipePrefs(ctx).left = it })
        addHomeSwipePicker(root, ctx, "Swipe right", { HomeSwipePrefs(ctx).right }, { HomeSwipePrefs(ctx).right = it })
        addHomeSwipePicker(root, ctx, "Swipe down",  { HomeSwipePrefs(ctx).down },  { HomeSwipePrefs(ctx).down = it })
        root.addView(caption(ctx,
            "Runtime overrides; defaults come from onehand.home_swipes, baked " +
            "into BuildConfig at build time."))
    }

    // ──────────────────────────── small builders ────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 22f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(4))
    }

    /** Section rule + heading — the three top-level parts of this page. */
    private fun section(root: LinearLayout, ctx: Context, t: String) {
        root.addView(View(ctx).apply {
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(20); bottomMargin = dp(12)
            }
        })
        root.addView(TextView(ctx).apply {
            text = t; textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun subhead(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0x99FFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        setPadding(0, 0, 0, dp(6))
    }

    private fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun buildOptions(cfg: OneHandConfig): List<Option> = buildList {
        add(Option("None", null))
        // In-app destinations, straight from circular_menu.actions — the same
        // entries the radial star shows, so the two menus offer one list.
        cfg.appActions.forEach {
            add(Option(it.label, GestureAction.AppTarget(it.target.removePrefix("action:"))))
        }
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
                // Spinner.setSelection POSTS its callback, so a listener attached
                // here still receives the PROGRAMMATIC selection and used to
                // persist it. Merely opening this screen therefore wrote an
                // override for every slot at whatever happened to be displayed —
                // and for an unmapped slot that is "None", which then shadowed
                // the build.json default forever. That is why a new default
                // could be shipped and never appear on a device that had once
                // visited this screen.
                //
                // Our post() is queued AFTER the one setSelection made, so by
                // the time suppress clears, the programmatic callback has been
                // and gone. Only real user choices are written.
                var suppress = true
                post { suppress = false }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (suppress) return
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
