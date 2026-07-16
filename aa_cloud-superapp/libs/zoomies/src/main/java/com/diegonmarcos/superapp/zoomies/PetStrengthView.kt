package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.ImageDecoder
import android.os.Build
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * A single animated pet that sits in status-bar Line 0 above its Line-1 tool.
 *
 * The animal is FIXED per tool ([setAnimal]); the tool's strength only changes
 * the GAIT ([setGait]) — idle → walk → walk_fast → run — so a weak tool ambles
 * and a strong one sprints. Frames come from the vendored zoomies GIFs at
 * `assets/zoomies/<animal>/<variant>_<gait>.gif` (CC BY-ND, used unmodified).
 *
 * Rendering is dependency-free: API 28+ decodes the GIF to an
 * [AnimatedImageDrawable] and plays it; API 26-27 shows a static first frame
 * (still conveys which animal/level, just not animated).
 */
class PetStrengthView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : AppCompatImageView(context, attrs, defStyle) {

    private var animal: String = ""
    private var variant: String = ""
    private var gait: String = ""

    /** Configs → Launcher → Others → "Animal animations". OFF = don't render
     *  the pet at all (view GONE); ON = render + animate. */
    var animate: Boolean = true
        set(v) {
            if (v == field) return
            field = v
            if (v) { visibility = VISIBLE; if (gait.isNotEmpty()) load() }
            else   { visibility = GONE; setImageDrawable(null) }
        }

    /** Fix the animal + colour variant for this tool. Reloads if a gait is set. */
    fun setAnimal(animal: String, variant: String) {
        if (animal == this.animal && variant == this.variant) return
        this.animal = animal
        this.variant = variant
        if (animate && gait.isNotEmpty()) load()
    }

    /** Set the gait (idle/walk/walk_fast/run) from the tool's current strength.
     *  No-ops if unchanged, so the per-tool ticker can call it freely. */
    fun setGait(gait: String) {
        if (gait == this.gait) return
        this.gait = gait
        if (animate && animal.isNotEmpty()) load()
    }

    /** Resolve the gait GIF, falling back to an available gait when the exact
     *  one isn't bundled (e.g. 3-gait animals like skeleton have no walk_fast).
     *  Order: requested → idle → run → walk → walk_fast. */
    private fun resolvePath(): String? {
        val dir = "zoomies/$animal"
        val avail = runCatching { context.assets.list(dir)?.toSet() }.getOrNull()
            ?: return "$dir/${variant}_$gait.gif"
        for (g in listOf(gait, "idle", "run", "walk", "walk_fast")) {
            val f = "${variant}_$g.gif"
            if (avail.contains(f)) return "$dir/$f"
        }
        return null
    }

    private fun load() {
        if (!animate) return
        val path = resolvePath() ?: run { setImageDrawable(null); return }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(context.assets, path)
                val drawable = ImageDecoder.decodeDrawable(src)
                setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
            } else {
                // API 26-27: no AnimatedImageDrawable — first frame, static.
                context.assets.open(path).use { setImageBitmap(BitmapFactory.decodeStream(it)) }
            }
        }.onFailure {
            // Missing/oddly-encoded asset: leave the view blank rather than crash
            // the whole status strip.
            setImageDrawable(null)
        }
    }
}
