package com.diegonmarcos.superapp.fin

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * MyFin → Dashboard. Beta surface — renders entirely from
 * `build.json::ui.myfin_mock` (baked into BuildConfig.UI_MYFIN_MOCK_B64).
 * No real account aggregator yet; the goal is to validate layout +
 * navigation + data-driven contract before wiring Plaid / GoCardless /
 * OFX import.
 *
 * Sections (all rendered from JSON):
 *   • Net worth headline
 *   • Month overview (income / expenses / savings / savings-rate)
 *   • Accounts list
 *   • Top expense categories
 *   • Recent transactions
 *
 * Empty / null fields are silently skipped so the JSON block can be
 * trimmed without breaking the render.
 */
class MyFinDashboardFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)
        renderInto(ctx, root)
        return scroll
    }

    private fun renderInto(ctx: Context, root: LinearLayout) {
        val mock = loadMock() ?: run {
            root.addView(header(ctx, "MyFin · beta"))
            root.addView(caption(ctx, "No mock data — populate build.json::ui.myfin_mock and rebuild."))
            return
        }
        val currency = mock.optString("currency", "USD")
        val nf = currencyFormatter(currency)

        // ── Net worth headline ─────────────────────────────────────
        root.addView(header(ctx, "MyFin · beta"))
        val netWorth = mock.optDouble("net_worth", Double.NaN)
        if (!netWorth.isNaN()) {
            root.addView(bigKpi(ctx, "Net worth", nf.format(netWorth)))
            root.addView(spacer(ctx, dp(ctx, 8)))
        }

        // ── Month overview ────────────────────────────────────────
        val month = mock.optJSONObject("month")
        if (month != null) {
            val label = mock.optString("month_label", "This month")
            root.addView(subhead(ctx, label))
            root.addView(grid4(ctx,
                "Income"    to nf.format(month.optDouble("income", 0.0)),
                "Expenses"  to nf.format(month.optDouble("expenses", 0.0)),
                "Savings"   to nf.format(month.optDouble("savings", 0.0)),
                "Save rate" to "${month.optInt("savings_rate_pct", 0)}%",
            ))
        }

        // ── Accounts ──────────────────────────────────────────────
        val accounts = mock.optJSONArray("accounts")
        if (accounts != null && accounts.length() > 0) {
            root.addView(spacer(ctx, dp(ctx, 16)))
            root.addView(subhead(ctx, "Accounts"))
            for (i in 0 until accounts.length()) {
                val a = accounts.getJSONObject(i)
                val name = a.optString("name", "—")
                val type = a.optString("type", "")
                val bal  = a.optDouble("balance", 0.0)
                val label = if (type.isNotEmpty()) "$name  ·  $type" else name
                root.addView(kv(ctx, label, nf.format(bal), neg = bal < 0))
            }
        }

        // ── Top categories ────────────────────────────────────────
        val cats = mock.optJSONArray("top_categories")
        if (cats != null && cats.length() > 0) {
            root.addView(spacer(ctx, dp(ctx, 16)))
            root.addView(subhead(ctx, "Top expense categories — ${mock.optString("month_label", "this month")}"))
            for (i in 0 until cats.length()) {
                val c = cats.getJSONObject(i)
                val name = c.optString("name", "—")
                val amt  = c.optDouble("amount", 0.0)
                val pct  = c.optInt("pct", 0)
                root.addView(kv(ctx, "$name  ·  $pct%", nf.format(amt)))
            }
        }

        // ── Recent transactions ───────────────────────────────────
        val recent = mock.optJSONArray("recent_tx")
        if (recent != null && recent.length() > 0) {
            root.addView(spacer(ctx, dp(ctx, 16)))
            root.addView(subhead(ctx, "Recent transactions"))
            for (i in 0 until recent.length()) {
                val t = recent.getJSONObject(i)
                val date  = t.optString("date", "—")
                val payee = t.optString("payee", "—")
                val cat   = t.optString("category", "")
                val amt   = t.optDouble("amount", 0.0)
                val tile = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = dp(ctx, 10); setPadding(pad, pad, pad, pad)
                    setBackgroundColor(0x18FFFFFFL.toInt())
                    val m = dp(ctx, 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(0, m, 0, m) }
                }
                tile.addView(TextView(ctx).apply {
                    text = "$date  ·  $payee"
                    setTextColor(0xFFFFFFFFL.toInt())
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                tile.addView(TextView(ctx).apply {
                    text = if (cat.isNotEmpty()) "$cat  ·  ${nf.format(amt)}" else nf.format(amt)
                    setTextColor(if (amt < 0) 0xFFEF4444.toInt() else 0xFF22C55E.toInt())
                    setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
                root.addView(tile)
            }
        }

        root.addView(spacer(ctx, dp(ctx, 16)))
        root.addView(caption(ctx, "Beta · all numbers from build.json::ui.myfin_mock. Real aggregator (Plaid / GoCardless / OFX import) lands in a follow-up; this surface stays the same."))
    }

    private fun loadMock(): JSONObject? = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.UI_MYFIN_MOCK_B64, Base64.NO_WRAP)))
    }.getOrNull()

    private fun currencyFormatter(currency: String): NumberFormat {
        val nf = NumberFormat.getCurrencyInstance(Locale.US)
        runCatching { nf.currency = java.util.Currency.getInstance(currency) }
        nf.maximumFractionDigits = 2
        return nf
    }

    // ── Tiny render helpers ───────────────────────────────────────
    private fun bigKpi(ctx: Context, label: String, value: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
        setBackgroundColor(0x447C3AED.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        addView(TextView(ctx).apply {
            text = label.uppercase()
            setTextColor(0xCCFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            letterSpacing = 0.1f
        })
        addView(TextView(ctx).apply {
            text = value
            setTextColor(0xFFFFFFFFL.toInt())
            textSize = 28f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setPadding(0, dp(ctx, 4), 0, 0)
        })
    }

    private fun grid4(ctx: Context, vararg cells: Pair<String, String>): View {
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val m = dp(ctx, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, m, 0, m) }
        }
        for (row in cells.toList().chunked(2)) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((k, v) in row) r.addView(counterTile(ctx, k, v))
            grid.addView(r)
        }
        return grid
    }
    private fun counterTile(ctx: Context, k: String, v: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 12); setPadding(pad, pad, pad, pad)
        setBackgroundColor(0x18FFFFFFL.toInt())
        val m = dp(ctx, 4)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(m, m, m, m) }
        addView(TextView(ctx).apply {
            text = v
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        })
        addView(TextView(ctx).apply {
            text = k
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        })
    }
    private fun kv(ctx: Context, k: String, v: String, neg: Boolean = false): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = dp(ctx, 8); setPadding(0, pad, 0, pad)
        addView(TextView(ctx).apply {
            text = k
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(TextView(ctx).apply {
            text = v
            setTextColor(if (neg) 0xFFEF4444.toInt() else 0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
        })
    }
    private fun header(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFFFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun subhead(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFE9D8FD.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(0, 0, 0, dp(ctx, 4))
    }
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xAAFFFFFFL.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
    }
    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    @Suppress("UNUSED_PARAMETER")
    private fun unused(j: JSONArray) {}

    companion object { fun newInstance() = MyFinDashboardFragment() }
}
