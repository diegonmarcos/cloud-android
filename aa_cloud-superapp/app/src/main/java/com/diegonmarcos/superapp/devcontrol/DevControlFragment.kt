package com.diegonmarcos.superapp.devcontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Visible surface for the [DevControlServer] — shows the token and
 * port the user / external automation needs to call it, plus
 * copy-pasteable curl examples for each endpoint. Listed under
 * Configs · Dev Control.
 */
class DevControlFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val prefs = DevControlPrefs(ctx)
        val port = prefs.port
        val token = prefs.token

        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(column)

        column.addView(label(ctx, "Dev Control · localhost HTTP", titleSize = true))
        column.addView(caption(ctx,
            "A tiny HTTP server runs inside the app on 127.0.0.1. " +
            "Reachable from this device's shell (or anything bound to " +
            "loopback). Use the token below as the Bearer credential."))

        column.addView(label(ctx, "Endpoint", small = true))
        column.addView(mono(ctx, "http://127.0.0.1:$port"))
        column.addView(label(ctx, "Token", small = true))
        column.addView(mono(ctx, token))

        column.addView(label(ctx, "Curl examples", small = true))
        val examples = listOf(
            "curl http://127.0.0.1:$port/ping",
            "curl http://127.0.0.1:$port/info",
            "curl -H 'Authorization: Bearer $token' http://127.0.0.1:$port/state",
            "curl -XPOST -H 'Authorization: Bearer $token' 'http://127.0.0.1:$port/haptic?preset=gemini_stream'",
            "curl -XPOST -H 'Authorization: Bearer $token' 'http://127.0.0.1:$port/goto?target=section:tabs'",
            "curl -XPOST -H 'Authorization: Bearer $token' 'http://127.0.0.1:$port/action?type=check_updates'",
            "curl -XPOST -H 'Authorization: Bearer $token' http://127.0.0.1:$port/update",
            "curl -XPOST -H 'Authorization: Bearer $token' http://127.0.0.1:$port/restart",
        )
        for (ex in examples) column.addView(mono(ctx, ex))

        column.addView(label(ctx, "Preset haptics", small = true))
        column.addView(caption(ctx, "gemini_stream · tick · start · end"))

        return scroll
    }

    private fun label(ctx: Context, t: String, titleSize: Boolean = false, small: Boolean = false): TextView {
        return TextView(ctx).apply {
            text = t
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(when {
                titleSize -> android.R.style.TextAppearance_Material_Title
                small     -> android.R.style.TextAppearance_Material_Caption
                else      -> android.R.style.TextAppearance_Material_Body1
            })
            setPadding(0, dp(if (titleSize) 0 else 10), 0, dp(if (small) 4 else 6))
        }
    }

    private fun caption(ctx: Context, t: String): TextView =
        TextView(ctx).apply {
            text = t
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            alpha = 0.8f
        }

    private fun mono(ctx: Context, t: String): TextView =
        TextView(ctx).apply {
            text = t
            setTextColor(0xFFB794F4.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            val pad = dp(8); setPadding(pad, pad, pad, pad)
            setOnLongClickListener {
                val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clip?.setPrimaryClip(ClipData.newPlainText("Cloud SuperApp dev control", t))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                true
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = DevControlFragment() }
}
