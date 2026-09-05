package com.diegonmarcos.morpheus

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Cloud-Morpheus, v1.
 *
 * THE SPLIT: c3-watchdog keeps what is running ALIVE (reactive, one machine).
 * c3-morpheus decides what RUNS (intentional, the whole fleet). This app is
 * morpheus's face on a phone: the workflows, the probe registry, the fleet and
 * the PM board, in one shell.
 *
 * WHY IT IS MOSTLY WEBVIEWS, AND WHY THAT IS NOT A COP-OUT.
 * Dagu and Paca are real, live, authenticated surfaces that already draw
 * themselves. Paca in particular has NO board API — /api/health, /api/v1/boards
 * and /api/boards all 404 and every route 302s to auth.diegonmarcos.com behind
 * Authelia forward_auth — so the board IS the Paca app, and the SuperApp
 * already reached the same conclusion and renders it inline. Reimplementing
 * either would be inventing a second truth. ac_c3-watchdog is a WebView for
 * the same reason.
 *
 * NO DEAD BUTTONS. Every tab either shows a real surface or states, on screen,
 * exactly what is not wired and why. A page that fails to load says so instead
 * of going blank — which matters most for Boards, because Paca is MESH-ONLY
 * (there is no public edge certificate for paca.diegonmarcos.com) and a phone
 * off the mesh would otherwise show an empty white rectangle that reads like an
 * outage.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var notice: TextView
    private lateinit var noticeScroll: ScrollView
    private var current: Tab = Tab.WORKFLOWS

    private enum class Tab { WORKFLOWS, HEALTH, FLEET, BOARDS }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )

        val stack = FrameLayout(this)
        root.addView(stack, LinearLayout.LayoutParams(MATCH, 0, 1f))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true   // Dagu and Paca are both SPAs
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(BG)
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?, req: WebResourceRequest?, err: WebResourceError?
                ) {
                    // Main-frame failures only: a sub-resource that 404s is not
                    // the page being unreachable, and treating it as one would
                    // put a false outage over a page that rendered fine.
                    if (req?.isForMainFrame != true) return
                    showUnreachable(err?.description?.toString() ?: "no answer")
                }
            }
        }
        stack.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))

        notice = TextView(this).apply {
            setBackgroundColor(BG)
            setTextColor(Color.parseColor("#c8d4e0"))
            textSize = 15f
            setPadding(48, 64, 48, 64)
            gravity = Gravity.TOP
        }
        // Scrollable, because these notices are long on purpose: an
        // explanation that is cut off at the fold explains nothing.
        noticeScroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            visibility = View.GONE
            addView(notice)
        }
        stack.addView(noticeScroll, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)

        Tab.values().forEach { t -> tabs.addView(tabButton(t)) }
        select(Tab.WORKFLOWS)
    }

    private fun tabButton(t: Tab): Button = Button(this).apply {
        text = when (t) {
            Tab.WORKFLOWS -> "Workflows"
            Tab.HEALTH -> "Health"
            Tab.FLEET -> "Fleet"
            Tab.BOARDS -> "Boards"
        }
        isAllCaps = false
        setTextColor(Color.parseColor("#78c8ff"))
        setBackgroundColor(Color.parseColor("#101520"))
        setOnClickListener { select(t) }
    }

    private fun select(t: Tab) {
        current = t
        when (t) {
            // The Dagu web UI: the real workflow list, with the real Start
            // button. The SAME surface Cloud/C3/Observability already reads
            // through DaguClient — no second client, no second truth.
            Tab.WORKFLOWS -> loadUrl(BuildConfig.DAGU_URL)
            Tab.HEALTH -> showHealthRegistry()
            Tab.FLEET -> showFleet()
            // Paca, rendered inline. It has no board API to wrap.
            Tab.BOARDS -> loadUrl(BuildConfig.PACA_URL)
        }
    }

    private fun loadUrl(url: String) {
        noticeScroll.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl(url)
    }

    private fun showHtml(html: String) {
        noticeScroll.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun showNotice(text: String) {
        web.visibility = View.GONE
        notice.text = text
        noticeScroll.visibility = View.VISIBLE
    }

    /**
     * A page that would not load says so, and says what it means for THIS
     * target. Blank is the one thing it must never be — a white rectangle
     * where a board should be reads as "the board is gone".
     */
    private fun showUnreachable(reason: String) {
        val where = when (current) {
            Tab.BOARDS ->
                "Paca is MESH-ONLY. There is no public edge certificate for " +
                    "paca.diegonmarcos.com — it measures 000 from the public edge " +
                    "while the service is perfectly healthy on 10.0.0.6:8095.\n\n" +
                    "So this is almost certainly THIS PHONE being off the WireGuard " +
                    "mesh, and is NOT evidence that Paca is down. Connect the mesh " +
                    "and reopen the tab."
            Tab.WORKFLOWS ->
                "Dagu sits behind Authelia forward_auth. An unauthenticated " +
                    "request is answered with a redirect to auth.diegonmarcos.com, " +
                    "which is the edge working. A hard failure like this one is " +
                    "usually no route rather than Dagu being down."
            else -> "The page did not load."
        }
        showNotice("COULD NOT LOAD\n\n$reason\n\n$where")
    }

    /**
     * The probe registry — WHAT "HEALTHY" IS DECLARED TO MEAN.
     *
     * Declarations, and it says so at the top. This app does not run probes
     * and does not display live results: those are produced by the
     * cloud-health-*.sh family, scheduled by Dagu, and published to ntfy. A
     * tile here coloured green would be a colour with nothing behind it, which
     * is precisely the failure this whole product is trying to stop.
     *
     * The registry is the one shipped in the CLI (da_morpheus/data/probes.json),
     * copied into assets at build time so there is one file and not two.
     */
    private fun showHealthRegistry() {
        val raw = try {
            assets.open("probes.json").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            showNotice(
                "PROBE REGISTRY NOT READABLE\n\n${t.message}\n\n" +
                    "The app could not open its own copy of probes.json, so it is " +
                    "showing nothing rather than an empty list that would read as " +
                    "'no probes are declared'."
            )
            return
        }

        val html = StringBuilder(HEAD)
        html.append("<p class=b>DECLARATIONS ONLY. Nothing on this page was measured. ")
        html.append("The probes run in Dagu and report to ntfy; this is the inventory of ")
        html.append("what is declared, not a status board.</p>")
        try {
            val doc = JSONObject(raw)

            html.append("<h2>Endpoint probes</h2>")
            html.append("<p class=n>Measured by the <code>c3-morpheus</code> CLI ")
            html.append("(<code>c3-morpheus probe</code>). Three results, never two: ")
            html.append("up, down, and <b>unavailable</b> — could not measure. ")
            html.append("A probe that cannot run is neither healthy nor failing.</p>")
            val eps = doc.optJSONArray("endpoint_probes")
            for (i in 0 until (eps?.length() ?: 0)) {
                val p = eps!!.getJSONObject(i)
                html.append("<div class=r><b>${esc(p.optString("name"))}</b> ")
                html.append("<span class=t>${esc(p.optString("reach"))}</span><br>")
                html.append("<code>${esc(p.optString("url"))}</code><br>")
                html.append("<span class=n>${esc(p.optString("description"))}</span><br>")
                html.append("<span class=n>ntfy: ${esc(p.optString("topic"))}</span></div>")
            }

            html.append("<h2>Script probes</h2>")
            html.append("<p class=n>Owned by cloud-infra, scheduled by Dagu. ")
            html.append("Morpheus declares and validates them; it does not reimplement ")
            html.append("them, and v1 cannot trigger them — see below.</p>")
            val sps = doc.optJSONArray("script_probes")
            for (i in 0 until (sps?.length() ?: 0)) {
                val p = sps!!.getJSONObject(i)
                html.append("<div class=r><b>${esc(p.optString("name"))}</b><br>")
                html.append("<span class=n>${esc(p.optString("description"))}</span><br>")
                html.append("<span class=n>dag: ${esc(p.optString("dag"))} &middot; ")
                html.append("ntfy: ${esc(p.optString("topic"))}</span>")
                val defect = p.optString("known_defect")
                if (defect.isNotBlank()) {
                    html.append("<div class=d>KNOWN DEFECT — ${esc(defect)}</div>")
                }
                html.append("</div>")
            }
        } catch (t: Throwable) {
            showNotice(
                "PROBE REGISTRY IS NOT VALID JSON\n\n${t.message}\n\n" +
                    "Showing nothing rather than a partial list that would look complete."
            )
            return
        }

        html.append("<h2>Not wired</h2>")
        html.append("<div class=r>This app cannot START a probe or a workflow. ")
        html.append("Triggering is a privileged capability and the authenticated path ")
        html.append("is being built once, in <code>libs/ops</code>: Dagu ")
        html.append("<code>POST /api/v1/dags/{name}/start</code> with a fresh ")
        html.append("client_credentials token per run, plus a server-side GHA dispatch ")
        html.append("proxy. A second client here would be a second thing to keep in sync ")
        html.append("with Authelia. Until it lands, use the Start button in the ")
        html.append("Workflows tab, which is Dagu's own.</div>")
        html.append("</body></html>")
        showHtml(html.toString())
    }

    /**
     * The fleet control panel. The SuperApp already owns it — a Constellation
     * page managing ~57 apps — so this hands off to it rather than shipping a
     * second updater that would fight the first over package ids.
     *
     * When the SuperApp is not installed this says exactly that. It does not
     * silently do nothing, and it does not pretend a fleet panel exists here.
     */
    private fun showFleet() {
        val pm = packageManager
        val intent: Intent? = pm.getLaunchIntentForPackage(BuildConfig.SUPERAPP_PACKAGE)
        if (intent == null) {
            showNotice(
                "FLEET PANEL NOT AVAILABLE ON THIS DEVICE\n\n" +
                    "The fleet control panel is the SuperApp's Constellation page " +
                    "(${BuildConfig.SUPERAPP_PACKAGE}), which manages the whole app " +
                    "fleet and its updates. It is not installed here, so there is " +
                    "nothing to open.\n\n" +
                    "Morpheus deliberately does not ship a second fleet updater: two " +
                    "updaters keyed on the same package ids would fight each other."
            )
            return
        }
        showNotice(
            "Opening the fleet panel in the SuperApp (Constellation).\n\n" +
                "Morpheus does not ship its own — the SuperApp already manages the " +
                "fleet, and a second updater would fight it."
        )
        startActivity(intent)
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val BG = Color.parseColor("#0b0e14")

        const val HEAD = """<!doctype html><html><head><meta name=viewport
content="width=device-width,initial-scale=1"><style>
body{background:#0b0e14;color:#c8d4e0;font:15px/1.5 -apple-system,sans-serif;padding:16px;margin:0}
h2{color:#78c8ff;font-size:16px;margin:24px 0 8px}
code{color:#8fd6a0;font-size:13px;word-break:break-all}
.r{border-left:3px solid #2a3a4a;padding:8px 12px;margin:8px 0;background:#101520}
.n{color:#7f8c9a;font-size:13px}
.t{color:#ffb44a;font-size:12px;text-transform:uppercase}
.b{background:#1a1408;border-left:3px solid #ffb44a;padding:10px 12px;font-size:14px}
.d{background:#2a1010;border-left:3px solid #ff6b6b;color:#ffb0b0;padding:8px 10px;margin-top:8px;font-size:13px}
</style></head><body>"""
    }
}
