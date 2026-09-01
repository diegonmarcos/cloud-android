package com.diegonmarcos.cloudme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.cal.CalEngine
import com.diegonmarcos.superapp.cal.CalTodo
import com.diegonmarcos.superapp.cal.TodoStore
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agenda — the one section that reads live data with no other app in front of
 * it. Both tabs are this fragment in two modes, because an event and a task
 * are the same row with a different date field and the two lists would
 * otherwise be one class copied twice.
 *
 * Events come from [CalEngine] (the ICS subscriptions in data/calendars.json,
 * synced by libs:cal) and tasks from [TodoStore] (the CalDAV VTODO mirror).
 * Both read from local storage only — this fragment never syncs, so opening
 * the tab is instant and cannot block on a network the phone may not have.
 * Nothing on screen is invented: with no account connected both lists are
 * empty and say so.
 */
class AgendaFragment : Fragment() {

    private val mode: String get() = arguments?.getString(ARG_MODE) ?: MODE_EVENTS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 28))
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.me_bg))
            addView(col)
        }
        if (mode == MODE_TODOS) renderTodos(ctx, col) else renderEvents(ctx, col)
        return scroll
    }

    // ── events ───────────────────────────────────────────────────────

    private fun renderEvents(ctx: Context, col: LinearLayout) {
        val now = System.currentTimeMillis()
        val horizonMillis = HORIZON_DAYS * 24L * 60L * 60L * 1000L
        val events = runCatching { JSONArray(CalEngine(ctx).events(now, now + horizonMillis)) }
            .getOrDefault(JSONArray())

        if (events.length() == 0) {
            col.addView(emptyState(ctx, "No events yet",
                "Agenda renders from the local cache, never from the network, so the tab opens " +
                "instantly. On a fresh install that cache is empty until the subscriptions in " +
                "data/calendars.json have been fetched once — that first fetch is running now if " +
                "this is the first time you have opened the tab."))
            syncOnce(col)
            return
        }

        var lastDay = ""
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val start = e.optLong("start")
            // One heading per day rather than a date on every row: a day with
            // four events reads as a block, which is what an agenda is for.
            val day = DAY_FMT.format(Date(start))
            if (day != lastDay) { col.addView(heading(ctx, day)); lastDay = day }
            col.addView(row(
                ctx,
                title = e.optString("title").ifBlank { "(untitled)" },
                detail = e.optString("location"),
                trailing = if (e.optBoolean("allDay")) "all day" else TIME_FMT.format(Date(start)),
                accent = 0xFF7E57C2.toInt(),
            ))
        }
    }

    // ── todos ────────────────────────────────────────────────────────

    private fun renderTodos(ctx: Context, col: LinearLayout) {
        val todos = runCatching { TodoStore.allTodos(ctx) }.getOrDefault(emptyList())
        // COMPLETED and CANCELLED are history; an open list that shows them is
        // a list you stop reading.
        val open = todos.filter { it.status != "COMPLETED" && it.status != "CANCELLED" }
            .sortedWith(compareBy({ it.dueUtcMillis ?: Long.MAX_VALUE }, { it.summary }))

        if (open.isEmpty()) {
            col.addView(emptyState(ctx, "No open tasks",
                "Tasks are the CalDAV VTODOs libs:cal mirrors locally. Buro paperwork and health " +
                "follow-ups both land here as long as they are filed in the same account — the " +
                "split the user asked for is a category on the task, not a second list."))
            return
        }

        val overdue = open.filter { it.dueUtcMillis != null && it.dueUtcMillis!! < System.currentTimeMillis() }
        if (overdue.isNotEmpty()) {
            col.addView(heading(ctx, "Overdue"))
            overdue.forEach { col.addView(todoRow(ctx, it, 0xFFEF5350.toInt())) }
        }
        val rest = open - overdue.toSet()
        if (rest.isNotEmpty()) {
            col.addView(heading(ctx, "Open"))
            rest.forEach { col.addView(todoRow(ctx, it, 0xFF26A69A.toInt())) }
        }
    }

    private fun todoRow(ctx: Context, t: CalTodo, accent: Int): View = row(
        ctx,
        title = t.summary.ifBlank { "(untitled)" },
        detail = t.description.lineSequence().firstOrNull().orEmpty(),
        trailing = t.dueUtcMillis?.let { DAY_FMT.format(Date(it)) } ?: "",
        accent = accent,
        progress = t.percentComplete,
    )

    // ── view helpers ─────────────────────────────────────────────────

    private fun row(
        ctx: Context,
        title: String,
        detail: String,
        trailing: String,
        accent: Int,
        progress: Int? = null,
    ): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.me_surface))
                setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.me_outline))
            }
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply { rightMargin = dp(ctx, 10) }
        })
        head.addView(TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing.isNotBlank()) head.addView(TextView(ctx).apply {
            text = trailing
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 12f
        })
        card.addView(head)

        if (detail.isNotBlank()) card.addView(TextView(ctx).apply {
            text = detail
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 13f
            maxLines = 2
            setPadding(dp(ctx, 20), dp(ctx, 4), 0, 0)
        })

        if (progress != null && progress > 0) card.addView(TextView(ctx).apply {
            text = "$progress% done"
            setTextColor(accent)
            textSize = 12f
            setPadding(dp(ctx, 20), dp(ctx, 4), 0, 0)
        })

        return card
    }

    private fun heading(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(ctx, 16), 0, dp(ctx, 2))
    }

    private fun emptyState(ctx: Context, title: String, body: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(ContextCompat.getColor(ctx, R.color.me_surface))
            setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.me_outline))
        }
        setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 10) }
        addView(TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(ctx).apply {
            text = body
            setTextColor(ContextCompat.getColor(ctx, R.color.me_text_dim))
            textSize = 13f
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setPadding(0, dp(ctx, 6), 0, 0)
        })
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * First-open fetch. [CalEngine.sync] is blocking and networked, so it never
     * runs on the way to drawing a frame — the cache is rendered first and the
     * fetch only fills it for next time, re-rendering in place if the fragment
     * is still on screen.
     *
     * Once per process, guarded rather than checked-then-set: two tab switches
     * in quick succession would otherwise both see an empty cache and start
     * their own fetch of the same feeds.
     */
    private fun syncOnce(col: LinearLayout) {
        if (!syncStarted.compareAndSet(false, true)) return
        val app = requireContext().applicationContext
        Thread {
            runCatching { CalEngine(app).sync() }
            col.post {
                if (!isAdded) return@post
                col.removeAllViews()
                renderEvents(col.context, col)
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val MODE_EVENTS = "events"
        const val MODE_TODOS = "todos"

        private const val ARG_MODE = "mode"
        private const val HORIZON_DAYS = 30

        /** Process-wide: the cache is shared by both tabs and by every
         *  fragment instance, so the fetch belongs to the process, not to a
         *  view that is recreated on every rotation. */
        private val syncStarted = AtomicBoolean(false)

        private val DAY_FMT = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun newInstance(mode: String): AgendaFragment = AgendaFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode) }
        }
    }
}
