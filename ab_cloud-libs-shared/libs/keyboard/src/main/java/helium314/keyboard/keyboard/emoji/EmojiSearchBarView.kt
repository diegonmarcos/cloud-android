// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.diegonmarcos.superapp.media.GifProvider
import com.diegonmarcos.superapp.media.MediaCommit
import com.diegonmarcos.superapp.media.MediaItem
import com.diegonmarcos.superapp.media.MediaRuntime
import com.diegonmarcos.superapp.media.StickerRepo
import helium314.keyboard.keyboard.internal.keyboard_parser.getEmojiDefaultVersion
import helium314.keyboard.latin.SingleDictionaryFacilitator
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import java.util.Locale
import java.util.concurrent.Executors

/**
 * SuperApp addition (patch 0011) — replaces the always-visible in-panel emoji
 * search field (patch 0010's `emoji_search_bar`, which permanently ate vertical
 * space and only searched emoji) with a Gboard/translate-bar-style toggle: tap
 * the search icon in the emoji tab strip, a bar is drawn above the keyboard
 * (keyboard stays visible, same hosting mechanism as [helium314.keyboard.latin.LatinIME.toggleTranslateBar]),
 * with three result rows below it — Emoji / Stickers / GIFs, all sized to match
 * -- tapping any result inserts it (text commit for emoji, commitContent for
 * sticker/gif) and closes the bar.
 *
 * Like TranslateBarView, key presses are routed here manually via
 * appendCodePoint/backspace (LatinIME.onEvent) rather than via a focusable
 * EditText, because the on-screen keyboard only ever commits to the app's own
 * InputConnection, not to arbitrary child views of the IME's window.
 */
class EmojiSearchBarView(context: Context) : LinearLayout(context) {

    fun interface IcProvider { fun get(): InputConnection? }
    fun interface MediaPicker { fun commit(info: InputContentInfoCompat) }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val ui = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    private var icp: IcProvider? = null
    private var mediaPicker: MediaPicker? = null
    private var onClose: Runnable? = null
    private var locale: Locale = Locale.getDefault()

    private val buffer = StringBuilder()
    private val inputView: TextView
    private val emojiRow: LinearLayout
    private val stickerRow: LinearLayout
    private val gifRow: LinearLayout
    private val emptyLabel: TextView

    private var dictionaryFacilitator: SingleDictionaryFacilitator? = null
    private var dictionaryLocale: Locale? = null
    private var searchToken = 0

    companion object {
        private const val CELL_DP = 40
    }

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(8))
        setBackgroundColor(0xF21B1B20.toInt())

        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inputView = TextView(context).apply {
            textSize = 16f; setTextColor(Color.WHITE); maxLines = 1
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        row.addView(inputView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = "✕"; setTextColor(0xFFB0B0B8.toInt()); textSize = 16f
            setPadding(dp(10), dp(2), dp(4), dp(2)); isClickable = true
            setOnClickListener { onClose?.run() }
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        emptyLabel = TextView(context).apply {
            text = "Type to search emoji, stickers and GIFs…"
            setTextColor(0xFF6E6E78.toInt()); setTypeface(null, Typeface.ITALIC)
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        addView(emptyLabel)

        emojiRow = resultRow()
        stickerRow = resultRow()
        gifRow = resultRow()
        renderInput()
    }

    private fun resultRow(): LinearLayout {
        val strip = LinearLayout(context).apply { orientation = HORIZONTAL }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip, stripLayoutParams())
        }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, dp(CELL_DP + 8)))
        return strip
    }

    private fun stripLayoutParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)

    /** Wire the bar to the IME. Mirrors TranslateBarView.bind. */
    fun bind(provider: IcProvider, picker: MediaPicker, currentLocale: Locale, onCloseAction: Runnable) {
        icp = provider
        mediaPicker = picker
        locale = currentLocale
        onClose = onCloseAction
    }

    fun onShown() {
        buffer.setLength(0)
        renderInput()
        clearResults()
    }

    fun appendCodePoint(cp: Int) { buffer.appendCodePoint(cp); onChanged() }
    fun backspace() {
        if (buffer.isNotEmpty()) {
            val last = buffer.length - 1
            if (last > 0 && Character.isLowSurrogate(buffer[last]) && Character.isHighSurrogate(buffer[last - 1]))
                buffer.setLength(last - 1) else buffer.setLength(last)
        }
        onChanged()
    }

    private fun onChanged() {
        renderInput()
        pending?.let { ui.removeCallbacks(it) }
        val text = buffer.toString().trim()
        if (text.isEmpty()) { clearResults(); return }
        val job = Runnable { search(text) }
        pending = job
        ui.postDelayed(job, 200)
    }

    private fun clearResults() {
        emojiRow.removeAllViews(); stickerRow.removeAllViews(); gifRow.removeAllViews()
        emptyLabel.visibility = View.VISIBLE
        emptyLabel.text = "Type to search emoji, stickers and GIFs…"
    }

    private fun renderInput() {
        inputView.text = if (buffer.isEmpty()) "" else buffer
    }

    private fun search(query: String) {
        val token = ++searchToken
        io.submit {
            val emojis = searchEmoji(query)
            val stickers = searchStickers(query)
            val gifs = searchGifs(query)
            main.post {
                if (token != searchToken) return@post
                emptyLabel.visibility =
                    if (emojis.isEmpty() && stickers.isEmpty() && gifs.isEmpty()) View.VISIBLE else View.GONE
                emptyLabel.text = "No results."
                fillEmojiRow(emojis)
                fillMediaRow(stickerRow, stickers)
                fillMediaRow(gifRow, gifs)
            }
        }
    }

    // ── emoji (on-device dictionary — same source as EmojiSearchActivity) ────
    private fun searchEmoji(query: String): List<String> {
        if (dictionaryFacilitator == null || dictionaryLocale != locale) {
            dictionaryFacilitator?.closeDictionaries()
            dictionaryFacilitator = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, context)
                ?.let { DictionaryFactory.getDictionary(it, locale) }?.let { SingleDictionaryFacilitator(it) }
            dictionaryLocale = locale
        }
        val facilitator = dictionaryFacilitator ?: return emptyList()
        return facilitator.getSuggestions(query.splitOnWhitespace())
            .filter { it.isEmoji }
            .map { getEmojiDefaultVersion(it.word) }
            .take(30)
    }

    // ── stickers — no per-sticker metadata exists, so match by pack name ─────
    private fun searchStickers(query: String): List<MediaItem> {
        val packs = StickerRepo.packs(context).filter { it.name.contains(query, ignoreCase = true) }
        return packs.flatMap { StickerRepo.stickers(context, "${it.authority} ${it.identifier}") }.take(24)
    }

    // ── gifs — real query against the configured provider ────────────────────
    private fun searchGifs(query: String): List<MediaItem> {
        val conf = MediaRuntime.gif ?: return emptyList()
        if (conf.apiKey.isBlank()) return emptyList()
        return runCatching { GifProvider.from(conf).fetch(query, 24) }.getOrDefault(emptyList())
    }

    private fun fillEmojiRow(emojis: List<String>) {
        emojiRow.removeAllViews()
        emojis.forEach { emoji ->
            emojiRow.addView(TextView(context).apply {
                text = emoji; textSize = 22f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(CELL_DP), dp(CELL_DP))
                isClickable = true
                setOnClickListener { commitEmoji(emoji) }
            })
        }
    }

    private fun fillMediaRow(row: LinearLayout, items: List<MediaItem>) {
        row.removeAllViews()
        items.forEach { item ->
            row.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(CELL_DP), dp(CELL_DP)).apply {
                    marginEnd = dp(4)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                isClickable = true
                setOnClickListener { commitMedia(item) }
                MediaRuntime.load(context, this, item.previewUrl)
            })
        }
    }

    private fun commitEmoji(emoji: String) {
        icp?.get()?.commitText(emoji, 1)
        onClose?.run()
    }

    private fun commitMedia(item: MediaItem) {
        val picker = mediaPicker ?: return
        io.submit {
            val info = MediaCommit.toContent(context, item) ?: return@submit
            main.post { picker.commit(info); onClose?.run() }
        }
    }

    /** Called from LatinIME.onDestroy, mirrors EmojiSearchActivity.Companion.closeDictionaryFacilitator. */
    fun closeDictionaryFacilitator() {
        dictionaryFacilitator?.closeDictionaries()
        dictionaryFacilitator = null
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
