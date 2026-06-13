package com.diegonmarcos.superapp.floatingnav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import org.json.JSONObject

/**
 * "Cloud SuperApp - Notification Center Infos" — a WhatsApp-style grouped
 * notification. The COLLAPSED bundle shows a summary listing the groups;
 * expanding the bundle reveals one child per group; expanding a child shows
 * that group's messages (InboxStyle). Data-driven from
 * build.json::ui.notification_infos (UI_NOTIFICATION_INFOS_B64) — sample data
 * for now, point it at a real feed later.
 */
class InfosNotifier(private val ctx: Context) {

    data class Group(val title: String, val messages: List<String>)

    private var lastCount = 0
    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun refresh() {
        if (!enabled()) { cancel(); return }
        val groups = parse()
        if (groups.isEmpty()) { cancel(); return }
        ensureChannel()

        groups.forEachIndexed { i, g ->
            val inbox = NotificationCompat.InboxStyle().setBigContentTitle(g.title)
            g.messages.forEach { inbox.addLine(it) }
            inbox.setSummaryText("${g.messages.size} new")
            val child = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(g.title)
                .setContentText(g.messages.firstOrNull() ?: "")
                .setGroup(GROUP_KEY)
                .setOnlyAlertOnce(true)
                .setStyle(inbox)
                .build()
            runCatching { nm().notify(NOTIF_BASE + i, child) }
        }

        // Group summary — collapsed bundle shows the group list.
        val summary = NotificationCompat.InboxStyle()
            .setBigContentTitle("Cloud SuperApp - Notification Center Infos")
        groups.forEach { summary.addLine("${it.title} · ${it.messages.size} new") }
        summary.setSummaryText("${groups.size} groups")
        val sum = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Infos")
            .setContentText("${groups.size} groups")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setStyle(summary)
            .build()
        runCatching { nm().notify(NOTIF_SUMMARY, sum) }
        lastCount = groups.size
    }

    fun cancel() {
        runCatching { nm().cancel(NOTIF_SUMMARY) }
        for (i in 0 until maxOf(lastCount, 32)) runCatching { nm().cancel(NOTIF_BASE + i) }
        lastCount = 0
    }

    private fun enabled(): Boolean = runCatching {
        JSONObject(decode()).optBoolean("enabled", true)
    }.getOrDefault(true)

    /** Visible for tests. */
    fun parse(): List<Group> {
        val o = runCatching { JSONObject(decode()) }.getOrDefault(JSONObject())
        val arr = o.optJSONArray("groups") ?: return emptyList()
        val out = mutableListOf<Group>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val title = g.optString("title")
            if (title.isBlank()) continue
            val msgs = mutableListOf<String>()
            g.optJSONArray("messages")?.let { ma ->
                for (j in 0 until ma.length()) ma.optString(j).takeIf { it.isNotBlank() }?.let(msgs::add)
            }
            out.add(Group(title, msgs))
        }
        return out
    }

    private fun decode(): String = runCatching {
        String(android.util.Base64.decode(BuildConfig.UI_NOTIFICATION_INFOS_B64, android.util.Base64.DEFAULT))
    }.getOrDefault("{}")

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm().getNotificationChannel(CHANNEL_ID) == null) {
            nm().createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Infos", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Grouped Cloud SuperApp infos."; setShowBadge(false) })
        }
    }

    companion object {
        private const val CHANNEL_ID = "floating_nav_infos"
        private const val GROUP_KEY = "com.diegonmarcos.superapp.infos"
        private const val NOTIF_SUMMARY = 0xF400
        private const val NOTIF_BASE = 0xF401
    }
}
