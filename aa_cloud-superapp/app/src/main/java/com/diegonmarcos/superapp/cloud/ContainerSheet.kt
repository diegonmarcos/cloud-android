package com.diegonmarcos.superapp.cloud

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout

/**
 * What a container icon opens on the C3 page.
 *
 * Every tile on that dashboard IS a docker/podman container, and tapping one
 * used to do exactly one thing — open its URL in the browser. That threw away
 * everything the estate already declares about the box and left no way to act
 * on it from the phone at all.
 *
 * The sheet has two tabs, each split the same way:
 *
 *   Infos    | Container: where it runs, how it is addressed
 *            | App:       what it serves and who may reach it
 *   Actions  | Container: start · stop · restart · update
 *            | App:       open · copy · service start · service stop
 *
 * THE SPLIT IS NOT COSMETIC. c3-infra-api addresses a container and its
 * service through different routes (/containers/{name} vs /services/{service}),
 * because restarting the box and restarting what runs inside it are different
 * operations with different blast radius. The UI shows them apart for the same
 * reason.
 *
 * Everything under Infos is DECLARATIVE — read from the same
 * data/services_{public,private}.json the dashboard itself is built from, so
 * the sheet cannot disagree with the tile that opened it, and it renders
 * instantly with no network call. Only Actions talk to the network.
 */
object ContainerSheet {

    fun show(activity: FragmentActivity, containerName: String, label: String, openUrl: String) {
        val ctx = activity
        val pub = Sections.publicServices().firstOrNull { it.name == containerName }
        val priv = Sections.privateServices().firstOrNull { it.name == containerName }

        // vm and service are what every ops route is keyed by; without a vm
        // there is nothing to POST to and the Actions tab says so rather than
        // offering buttons that would 404.
        val vm = pub?.vm ?: priv?.vm ?: ""
        val service = pub?.service ?: priv?.service ?: containerName

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF140E1F.toInt())
        }
        root.addView(header(ctx, label, containerName, vm))

        val pane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val tabs = TabLayout(ctx).apply {
            tabMode = TabLayout.MODE_FIXED
            setBackgroundColor(0xFF140E1F.toInt())
            setSelectedTabIndicatorColor(0xFFB794F6.toInt())
            setTabTextColors(0xFF9B93AB.toInt(), 0xFFB794F6.toInt())
            addTab(newTab().setText("Infos"))
            addTab(newTab().setText("Actions"))
        }
        root.addView(tabs)
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true
            addView(pane)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 420))
        })

        val dialog = BottomSheetDialog(ctx).apply { setContentView(root) }

        fun render(index: Int) {
            pane.removeAllViews()
            if (index == 0) renderInfos(ctx, pane, containerName, vm, service, pub, priv)
            else renderActions(ctx, pane, dialog, containerName, vm, service, pub, priv, openUrl)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = render(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        render(0)
        dialog.show()
    }

    // ── Infos ────────────────────────────────────────────────────────────

    private fun renderInfos(
        ctx: Context, pane: LinearLayout, name: String, vm: String, service: String,
        pub: Sections.PublicService?, priv: Sections.PrivateService?,
    ) {
        val card = card(ctx); pane.addView(card)
        card.addView(blockTitle(ctx, "Container"))
        card.addView(kv(ctx, "Name", name))
        card.addView(kv(ctx, "VM", vm.ifBlank { "—" }))
        card.addView(kv(ctx, "Private DNS", (pub?.privateDns ?: priv?.privateDns).orEmpty().ifBlank { "—" }))
        card.addView(kv(ctx, "Protocol", priv?.protocol.orEmpty().ifBlank { "—" }))
        card.addView(kv(ctx, "Category", (pub?.category ?: priv?.category).orEmpty().ifBlank { "—" }))
        priv?.dbEngine?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { card.addView(kv(ctx, "DB engine", it)) }

        val card2 = card(ctx); pane.addView(card2)
        card2.addView(blockTitle(ctx, "App"))
        card2.addView(kv(ctx, "Service", service))
        card2.addView(kv(ctx, "Public URL", pub?.publicUrl.orEmpty().ifBlank { "not published" }))
        card2.addView(kv(ctx, "Auth", pub?.auth.orEmpty().ifBlank { if (pub == null) "private (mesh only)" else "—" }))
        card2.addView(kv(ctx, "Exposure", if (pub != null) "public via Caddy" else "private — reachable over WireGuard only"))

        // App-specific configuration. The generic fields above describe the box;
        // these describe the JOB — Caddy's routes, WireGuard's mesh, Authelia's
        // guard list. Long lists are expected here (41 public routes), so each
        // block is its own card and the pane scrolls.
        val extras = ContainerConfigs.forContainer(name)
        for (b in extras) {
            val c = card(ctx); pane.addView(c)
            c.addView(blockTitle(ctx, b.title))
            if (b.subtitle.isNotBlank()) c.addView(TextView(ctx).apply {
                text = b.subtitle
                setTextColor(0xFF6F6880.toInt()); textSize = 11f
                setPadding(0, 0, 0, dp(ctx, 2))
            })
            for ((k, v) in b.rows) c.addView(kv(ctx, k, v))
        }

        pane.addView(note(ctx,
            "Declared, not probed. Every value above comes from data/services_public.json, " +
            "data/services_private.json and data/mesh.json — the same files the dashboard tile " +
            "was built from, so this sheet cannot disagree with the icon that opened it, and it " +
            "still reads correctly with the mesh down." +
            if (extras.isEmpty()) "\n\nNo app-specific config block is written for this " +
                "container yet; ContainerConfigs is where one goes." else ""))
    }

    // ── Actions ──────────────────────────────────────────────────────────

    private fun renderActions(
        ctx: Context, pane: LinearLayout, dialog: BottomSheetDialog,
        name: String, vm: String, service: String,
        pub: Sections.PublicService?, priv: Sections.PrivateService?, openUrl: String,
    ) {
        val status = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextColor(0xFF9B93AB.toInt())
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 4))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        val bearer = runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("")

        val card = card(ctx); pane.addView(card)
        card.addView(blockTitle(ctx, "Container"))
        if (vm.isBlank()) {
            card.addView(note(ctx, "No VM recorded for $name, so there is no /vms/{vm}/containers/ " +
                "path to call. Add it to data/services_*.json and the buttons appear."))
        } else {
            for ((verb, act) in listOf("Start" to "start", "Stop" to "stop",
                                       "Restart" to "restart", "Update" to "update")) {
                card.addView(action(ctx, verb, "$act container") {
                    dispatch(status) { OpsClient.container(vm, name, act, bearer) }
                })
            }
        }

        val card2 = card(ctx); pane.addView(card2)
        card2.addView(blockTitle(ctx, "App"))
        val url = pub?.publicUrl?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else "https://$it"
        } ?: openUrl
        if (url.isNotBlank()) {
            card2.addView(action(ctx, "Open", url.removePrefix("https://")) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                dialog.dismiss()
            })
        }
        (pub?.privateDns ?: priv?.privateDns)?.takeIf { it.isNotBlank() }?.let { dns ->
            card2.addView(action(ctx, "Copy private DNS", dns) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(name, dns))
                show(status, "Copied $dns")
            })
        }
        if (vm.isNotBlank()) {
            for ((verb, act) in listOf("Start service" to "start", "Stop service" to "stop")) {
                card2.addView(action(ctx, verb, "$act $service") {
                    dispatch(status) { OpsClient.service(vm, service, act, bearer) }
                })
            }
        }

        pane.addView(status)
        if (bearer.isBlank() && vm.isNotBlank()) {
            pane.addView(note(ctx,
                "The four container actions and the two service actions need an Authelia bearer — " +
                "they sit behind forward_auth. This device has none stored. Import one via " +
                "Configs → Profile → Config import, or paste it in the Dagu login; both write the " +
                "same token. Open and Copy work without it."))
        }
    }

    private fun dispatch(status: TextView, call: () -> OpsClient.Outcome) {
        show(status, "… working")
        Thread {
            val r = call()
            status.post {
                when (r) {
                    is OpsClient.Outcome.Ok -> show(status, r.message, ok = true)
                    is OpsClient.Outcome.Failed -> show(status, "✗ ${r.kind}\n${r.message}", ok = false)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ── small view helpers ───────────────────────────────────────────────

    private fun show(t: TextView, text: String, ok: Boolean? = null) {
        t.visibility = View.VISIBLE
        t.text = text
        t.setTextColor(when (ok) { true -> 0xFF34C759.toInt(); false -> 0xFFFF6B6B.toInt(); null -> 0xFF9B93AB.toInt() })
    }

    private fun header(ctx: Context, label: String, name: String, vm: String) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 8))
            addView(TextView(ctx).apply {
                text = label
                setTextColor(0xFFECE6F5.toInt()); textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = if (vm.isBlank()) name else "$name · $vm"
                setTextColor(0xFF9B93AB.toInt()); textSize = 12f
            })
        }

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(0xFF1B1428.toInt())
            setStroke(dp(ctx, 1), 0xFF322A44.toInt())
        }
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), 0) }
    }

    private fun blockTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFB794F6.toInt()); textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(ctx, 4))
    }

    private fun kv(ctx: Context, k: String, v: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 6), 0, 0)
        addView(TextView(ctx).apply {
            text = k; setTextColor(0xFF9B93AB.toInt()); textSize = 13f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply {
            text = v; setTextColor(0xFFECE6F5.toInt()); textSize = 13f
            gravity = Gravity.END; setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
    }

    private fun action(ctx: Context, label: String, detail: String, onTap: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            setOnClickListener { onTap() }
            addView(TextView(ctx).apply {
                text = label; setTextColor(0xFFECE6F5.toInt()); textSize = 14f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = detail; setTextColor(0xFF6F6880.toInt()); textSize = 11f
                gravity = Gravity.END; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        }

    private fun note(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFF6F6880.toInt()); textSize = 12f
        setLineSpacing(dp(ctx, 2).toFloat(), 1f)
        setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 16))
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
