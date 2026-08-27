package com.diegonmarcos.cloudnav

import android.content.Context
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.google.android.material.card.MaterialCardView

/**
 * Builders for the per-page floating search cards. Each tab renders its own
 * search UI (Routes = multi-stop, Navigation = destination, Places = simple
 * POI lookup), but they share one rounded-pill look so the app feels
 * consistent. Pure view code — no data hardcoding.
 */
object SearchUi {

    /** A rounded Google-Maps-style search pill: leading icon + single-line
     *  EditText. [onSubmit] fires on the IME search/done action (and hides
     *  the keyboard). Returns the card (add it to your layout) — the field
     *  is reachable via [field]. */
    fun searchCard(
        ctx: Context,
        hint: String,
        iconRes: Int = R.drawable.ic_search,
        onSubmit: (String) -> Unit,
    ): MaterialCardView {
        val card = MaterialCardView(ctx).apply {
            radius = dp(ctx, 26f)
            cardElevation = dp(ctx, 4f)
            useCompatPadding = true
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16f).toInt(), dp(ctx, 2f).toInt(), dp(ctx, 12f).toInt(), dp(ctx, 2f).toInt())
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(iconRes)
            val s = dp(ctx, 22f).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setColorFilter(ContextCompat.getColor(ctx, R.color.nav_on_surface_variant))
        })
        val field = EditText(ctx).apply {
            this.hint = hint
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setPadding(dp(ctx, 12f).toInt(), 0, 0, 0)
            setTextColor(ContextCompat.getColor(ctx, R.color.nav_on_surface))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.nav_on_surface_variant))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 48f).toInt(), 1f)
            tag = "search_field"
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    onSubmit(v.text.toString())
                    hideKeyboard(ctx, v)
                    true
                } else false
            }
        }
        row.addView(field)
        card.addView(row)
        card.tag = field   // so callers can grab the field off the card.
        return card
    }

    /** The EditText inside a [searchCard]. */
    fun field(card: MaterialCardView): EditText = card.tag as EditText

    /**
     * Offer the top forward-search candidates and let the user pick the right
     * one (instead of auto-taking the first hit). Shows up to
     * [maps.BuildConfig.UI_SEARCH_CHOICES] results; if only one, picks it
     * directly. Caller handles the empty case before calling.
     */
    fun chooseResult(
        ctx: Context,
        hits: List<MapsProviderClient.SearchHit>,
        onPick: (MapsProviderClient.SearchHit) -> Unit,
    ) {
        if (hits.isEmpty()) return
        val n = com.diegonmarcos.cloudnav.maps.BuildConfig.UI_SEARCH_CHOICES.coerceAtLeast(1)
        val top = hits.take(n)
        if (top.size == 1) { onPick(top[0]); return }
        val labels = top.map { h ->
            if (h.subtitle.isNullOrBlank()) h.title else "${h.title}\n${h.subtitle}"
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Pick the right place")
            .setItems(labels) { _, which -> onPick(top[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    private fun hideKeyboard(ctx: Context, view: android.view.View) {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
