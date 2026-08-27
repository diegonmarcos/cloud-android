package com.diegonmarcos.superapp.media

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import java.util.concurrent.Executors

/**
 * The Sticker / GIF body of the emoji surface. Owns ROW 2 — the per-type
 * category tab strip (GIF categories from build.json, or one tab per installed
 * sticker pack) — and the preview grid below it. ROW 1 (Emoji/Sticker/GIF) is
 * owned by the keyboard's EmojiPalettesView (patch 0007), which toggles this
 * view's visibility and calls [showType].
 *
 * Hosted by libs:keyboard; picks are committed via [bind]'s callback, which the
 * keyboard wires to KeyboardActionListener.onContent → commitContent.
 */
class MediaPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val tabStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val grid = RecyclerView(context)
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER; setPadding(24, 48, 24, 48); visibility = View.GONE
    }
    private val adapter = ItemAdapter()

    /** SAM so the keyboard's Java EmojiPalettesView can wire it as a lambda. */
    fun interface Committer { fun commit(info: InputContentInfoCompat) }

    private var committer: Committer? = null
    private var type: MediaType = MediaType.GIF
    private var stickerPacks: List<StickerRepo.Pack> = emptyList()
    private var loadToken = 0  // guards against stale async results after a tab switch

    init {
        orientation = VERTICAL
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val body = FrameLayout(context)
        grid.layoutManager = GridLayoutManager(context, 4)
        grid.adapter = adapter
        body.addView(grid, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        body.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Keyboard calls this once, handing us the commitContent sink. */
    fun bind(committer: Committer) { this.committer = committer }

    /** Switch the whole body to STICKER or GIF; rebuilds row-2 tabs. */
    fun showType(type: MediaType) {
        this.type = type
        (grid.layoutManager as GridLayoutManager).spanCount = if (type == MediaType.GIF) 3 else 4
        val categories = when (type) {
            MediaType.GIF -> MediaRuntime.gif?.categories ?: emptyList()
            MediaType.STICKER -> {
                stickerPacks = StickerRepo.packs(context) // cheap (cursor), fine on main
                stickerPacks.map { StickerRepo.packCategory(it) }
            }
            MediaType.EMOJI -> emptyList()
        }
        buildTabs(categories)
        if (categories.isEmpty()) showEmpty(emptyMessageFor(type))
        else selectCategory(categories.first())
    }

    private fun buildTabs(categories: List<MediaCategory>) {
        tabStrip.removeAllViews()
        categories.forEach { cat ->
            tabStrip.addView(TextView(context).apply {
                text = cat.label
                setPadding(28, 20, 28, 20)
                isSingleLine = true
                setOnClickListener { selectCategory(cat) }
            })
        }
    }

    private fun selectCategory(cat: MediaCategory) {
        highlightTab(cat.label)
        val token = ++loadToken
        showEmpty(null); adapter.submit(emptyList())
        io.submit {
            val items = when (type) {
                MediaType.GIF -> MediaRuntime.gif?.let {
                    if (it.apiKey.isBlank()) emptyList()
                    else GifProvider.from(it).fetch(cat.query, it.limit)
                } ?: emptyList()
                MediaType.STICKER -> StickerRepo.stickers(context, cat.id)
                MediaType.EMOJI -> emptyList()
            }
            main.post {
                if (token != loadToken) return@post  // a newer tab won
                if (items.isEmpty()) showEmpty(emptyMessageFor(type)) else { showEmpty(null); adapter.submit(items) }
            }
        }
    }

    private fun onPick(item: MediaItem) {
        val sink = committer ?: return
        io.submit {
            val info = MediaCommit.toContent(context, item) ?: return@submit
            main.post { sink.commit(info) }
        }
    }

    private fun highlightTab(label: String) {
        for (i in 0 until tabStrip.childCount) {
            val t = tabStrip.getChildAt(i) as TextView
            t.alpha = if (t.text == label) 1f else 0.55f
        }
    }

    private fun showEmpty(message: String?) {
        status.text = message ?: ""
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun emptyMessageFor(type: MediaType) = when (type) {
        MediaType.GIF ->
            if (MediaRuntime.gif == null) "GIFs are disabled."
            else if (!MediaRuntime.gifEnabled) "No GIF API key configured."
            else "No GIFs found."
        MediaType.STICKER -> "No sticker packs installed."
        MediaType.EMOJI -> ""
    }

    private inner class ItemAdapter : RecyclerView.Adapter<ItemAdapter.VH>() {
        private val items = ArrayList<MediaItem>()
        fun submit(list: List<MediaItem>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

        inner class VH(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val size = parent.width / (parent.let { (grid.layoutManager as GridLayoutManager).spanCount }.coerceAtLeast(1))
            val iv = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, size.coerceAtLeast(140))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(6, 6, 6, 6)
                setBackgroundColor(Color.TRANSPARENT)
            }
            return VH(iv)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.image.setOnClickListener { onPick(item) }
            MediaRuntime.imageLoader(context).enqueue(
                ImageRequest.Builder(context).data(item.previewUrl).target(holder.image).build()
            )
        }
    }
}
