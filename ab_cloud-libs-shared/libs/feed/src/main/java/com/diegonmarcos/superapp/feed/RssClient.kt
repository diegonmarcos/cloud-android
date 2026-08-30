package com.diegonmarcos.superapp.feed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal RSS 2.0 + Atom feed fetcher + parser. Handles both because
 * different sources use different schemas (Wired Atom, BBC RSS, etc).
 * No external dependency — Android's bundled XmlPullParser.
 */
object RssClient {

    data class Item(val title: String, val link: String, val date: String)

    /** Fetch + parse. Throws on network / XML errors — caller decides
     *  what to surface. */
    fun fetch(url: String, maxItems: Int = 25): List<Item> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout    = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CloudSuperApp/1.0 (+rss)")
            setRequestProperty("Accept",
                "application/rss+xml, application/atom+xml, application/xml, text/xml")
        }
        if (conn.responseCode !in 200..299) {
            throw java.io.IOException("HTTP ${conn.responseCode}")
        }
        conn.inputStream.use { stream ->
            return parse(InputStreamReader(stream, conn.contentEncoding ?: "UTF-8"), maxItems)
        }
    }

    private fun parse(reader: java.io.Reader, maxItems: Int): List<Item> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        val out = ArrayList<Item>(maxItems)
        var event = parser.eventType
        var inItem = false
        var title = ""; var link = ""; var date = ""

        while (event != XmlPullParser.END_DOCUMENT && out.size < maxItems) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    when (tag) {
                        "item", "entry" -> {
                            inItem = true; title = ""; link = ""; date = ""
                        }
                        "title" -> if (inItem) title = parser.nextText().trim()
                        "link"  -> if (inItem) {
                            // RSS: text content. Atom: href attribute.
                            val href = parser.getAttributeValue(null, "href")
                            link = href?.trim() ?: parser.nextText().trim()
                        }
                        "pubdate", "updated", "published", "dc:date" ->
                            if (inItem) date = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tag == "item" || tag == "entry") {
                        if (inItem && link.isNotEmpty() && title.isNotEmpty()) {
                            out.add(Item(title, link, date))
                        }
                        inItem = false
                    }
                }
            }
            event = parser.next()
        }
        return out
    }
}
