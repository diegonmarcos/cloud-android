package com.diegonmarcos.cloudcalendar

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import com.diegonmarcos.superapp.cal.CalSync
import com.diegonmarcos.superapp.cal.CalendarStore
import com.diegonmarcos.superapp.cal.Calendars
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * JS bridge exposed to calendar.html as `window.CalBridge`.
 *
 * SECURITY: this bridge is only safe to attach because the WebView it
 * is registered on loads a LOCAL asset we control
 * (file:///android_asset/calendar.html) — see MainActivity. Never
 * attach a JavascriptInterface bridge to a WebView that can navigate
 * to remote/attacker-controlled content: any page loaded there would
 * gain the same Java-callable surface as our own UI.
 */
class CalBridge(private val ctx: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var syncRunning = false
    @Volatile private var lastOk = 0
    @Volatile private var lastSkipped = 0
    @Volatile private var lastFailed = 0
    @Volatile private var lastMessages: List<String> = emptyList()

    private fun subscriptions() = Calendars.parse(
        String(Base64.decode(BuildConfig.CALENDARS_B64, Base64.DEFAULT), Charsets.UTF_8)
    )

    @JavascriptInterface
    fun calendars(): String {
        val arr = JSONArray()
        for (sub in subscriptions()) {
            arr.put(JSONObject().apply {
                put("id", sub.id)
                put("name", sub.name)
                put("category", sub.category)
                put("color", sub.color)
                put("enabled", sub.enabled)
            })
        }
        return arr.toString()
    }

    // fromUtcMillis/toUtcMillis are taken as String and parsed to Long here:
    // JS numbers passed over the WebView JS bridge are doubles, which lose
    // precision for 64-bit epoch-millis values, so callers must stringify
    // them before passing across the bridge.
    @JavascriptInterface
    fun events(fromUtcMillis: String, toUtcMillis: String): String {
        val from = fromUtcMillis.toLongOrNull() ?: 0L
        val to = toUtcMillis.toLongOrNull() ?: 0L
        val ids = subscriptions().map { it.id }
        val arr = JSONArray()
        for (e in CalendarStore.eventsFor(ctx, ids, from, to)) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("calendarId", e.calendarId)
                put("title", e.title)
                put("start", e.startUtcMillis)
                put("end", e.endUtcMillis)
                put("allDay", e.allDay)
                put("location", e.location)
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun sync(): String {
        if (!syncRunning) {
            syncRunning = true
            executor.execute {
                try {
                    val report = CalSync.syncAll(ctx, subscriptions())
                    lastOk = report.ok
                    lastSkipped = report.skipped
                    lastFailed = report.failed
                    lastMessages = report.messages
                } finally {
                    syncRunning = false
                }
            }
        }
        return JSONObject().put("started", true).toString()
    }

    @JavascriptInterface
    fun syncStatus(): String {
        val messages = JSONArray()
        for (m in lastMessages) messages.put(m)
        return JSONObject().apply {
            put("running", syncRunning)
            put("ok", lastOk)
            put("skipped", lastSkipped)
            put("failed", lastFailed)
            put("messages", messages)
        }.toString()
    }
}
