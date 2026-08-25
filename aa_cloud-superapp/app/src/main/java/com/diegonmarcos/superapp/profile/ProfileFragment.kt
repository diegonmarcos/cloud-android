package com.diegonmarcos.superapp.profile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Configs → Profile — three free-form fields (Name · Email · Initials)
 * bound to [ProfilePrefs]. Auto-saves on every text change (no explicit
 * Save button) — the drawer header reads from the same prefs on every
 * open, so changes are visible immediately next time the drawer slides in.
 */
class ProfileFragment : Fragment() {

    private lateinit var prefs: ProfilePrefs

    /** Gallery picker for the profile photo (round avatar). */
    private val picturePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveImage(it, isBanner = false) }
        }
    /** Gallery picker for the cover/banner photo (wide). */
    private val bannerPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveImage(it, isBanner = true) }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = ProfilePrefs(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 18); setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scroll.addView(col)

        col.addView(sectionHeader(ctx, "Profile"))
        col.addView(caption(ctx, "Edit your identity — auto-saved on change. Initials show in the drawer; the rest powers the Virtual Business Card."))

        col.addView(label(ctx, "Name"))
        col.addView(field(ctx, prefs.name) { prefs.name = it })

        col.addView(label(ctx, "Email"))
        col.addView(field(ctx, prefs.email) { prefs.email = it }.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        })

        col.addView(label(ctx, "Initials"))
        col.addView(field(ctx, prefs.initials) { prefs.initials = it }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        })

        col.addView(label(ctx, "Titles  (use ' | ' to separate)"))
        col.addView(field(ctx, prefs.titles) { prefs.titles = it }.apply {
            isSingleLine = false; maxLines = 4
        })

        col.addView(label(ctx, "Company"))
        col.addView(field(ctx, prefs.company) { prefs.company = it })

        col.addView(label(ctx, "Location"))
        col.addView(field(ctx, prefs.location) { prefs.location = it })

        col.addView(label(ctx, "Website"))
        col.addView(field(ctx, prefs.website) { prefs.website = it }.apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        })

        col.addView(label(ctx, "Profile picture"))
        col.addView(pickButton(ctx, prefs.pictureUri.ifBlank { "Pick from gallery…" }) {
            picturePicker.launch("image/*")
        })

        col.addView(label(ctx, "Banner photo"))
        col.addView(pickButton(ctx, prefs.bannerUri.ifBlank { "Pick from gallery…" }) {
            bannerPicker.launch("image/*")
        })

        return scroll
    }

    /** Copy the picked image into our cache dir + store the cached path
     *  in ProfilePrefs. We don't rely on the original `content://` URI
     *  surviving — the source app may revoke permission later. */
    private fun saveImage(uri: android.net.Uri, isBanner: Boolean) {
        runCatching {
            val ctx = requireContext()
            val name = if (isBanner) "profile_banner.png" else "profile_picture.png"
            val outFile = java.io.File(ctx.filesDir, name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            if (isBanner) prefs.bannerUri = outFile.absolutePath
            else          prefs.pictureUri = outFile.absolutePath
            // Re-render so the buttons show the new path.
            parentFragmentManager.beginTransaction().detach(this).commitNow()
            parentFragmentManager.beginTransaction().attach(this).commitNow()
        }
    }

    private fun pickButton(ctx: android.content.Context, currentLabel: String, onClick: () -> Unit): View {
        val tv = android.widget.TextView(ctx).apply {
            text = currentLabel
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF7C3AED.toInt())
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 4) }
        tv.layoutParams = lp
        return tv
    }

    private fun sectionHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 4))
        }

    private fun label(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            alpha = 0.85f
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        }

    private fun caption(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            setPadding(0, 0, 0, dp(ctx, 8))
        }

    private fun field(ctx: android.content.Context, initial: String, save: (String) -> Unit): EditText =
        EditText(ctx).apply {
            setText(initial)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { save(s?.toString().orEmpty()) }
            })
        }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(): ProfileFragment = ProfileFragment()
    }
}
