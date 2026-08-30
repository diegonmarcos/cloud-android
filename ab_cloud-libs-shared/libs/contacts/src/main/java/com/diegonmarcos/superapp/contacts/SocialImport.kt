package com.diegonmarcos.superapp.contacts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sniffs and parses the two data-export formats this app treats as
 * contact sources in their own right — LinkedIn's "Connections.csv" and
 * Instagram's followers/following JSON — straight into [RawContact]s.
 * Content-sniffed, not extension-sniffed: Android's document picker hands
 * back a content:// stream copied to an opaque temp name, so the only
 * reliable signal is what's inside the file. The sniffing/parsing rules
 * (BOM-tolerant head, CSV dialect, JSON shape) are ported verbatim from
 * the upstream org.fossify.contacts SocialImporter, which converted these
 * exports to vCard for the stock VcfImporter pipeline; this app has no
 * such pipeline (device contacts are read live via ContactsContract) so
 * the output stops one step earlier, as plain [RawContact]s that
 * MergeEngine folds in alongside everything else.
 */
object SocialImport {

    data class Result(val source: String, val contacts: List<RawContact>)

    fun parse(text: String): Result? = try {
        val head = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        when {
            head.startsWith("BEGIN:VCARD", ignoreCase = true) -> null
            (head.startsWith("[") || head.startsWith("{")) && text.contains("string_list_data") ->
                parseInstagramJson(head).takeIf { it.isNotEmpty() }?.let { Result("instagram", it) }

            looksLikeLinkedInCsv(head) ->
                parseLinkedInCsv(text).takeIf { it.isNotEmpty() }?.let { Result("linkedin", it) }

            else -> null
        }
    } catch (e: Exception) {
        // not a recognized social export
        null
    }

    private fun looksLikeLinkedInCsv(text: String) = text.lineSequence()
        .take(10)
        .any { it.contains("First Name") && it.contains("Last Name") }

    // ── LinkedIn Connections.csv ────────────────────────────────────────
    // Columns (by header name, order-independent): First Name, Last Name, URL,
    // Email Address, Company, Position, Connected On. Exports carry a "Notes:"
    // preamble before the header line — rows before the header are skipped.
    private fun parseLinkedInCsv(text: String): List<RawContact> {
        val rows = parseCsv(text)
        val headerIndex = rows.indexOfFirst { row ->
            row.any { it.trim() == "First Name" } && row.any { it.trim() == "Last Name" }
        }
        if (headerIndex == -1) return emptyList()

        val header = rows[headerIndex].map { it.trim() }
        fun col(row: List<String>, name: String) =
            header.indexOf(name).takeIf { it >= 0 && it < row.size }?.let { row[it].trim() } ?: ""

        return rows.drop(headerIndex + 1).mapNotNull { row ->
            val first = col(row, "First Name")
            val last = col(row, "Last Name")
            if (first.isEmpty() && last.isEmpty()) return@mapNotNull null

            val email = col(row, "Email Address")
            val url = col(row, "URL")
            RawContact(
                source = "linkedin",
                name = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" "),
                emails = if (email.isNotEmpty()) listOf(email) else emptyList(),
                org = col(row, "Company"),
                title = col(row, "Position"),
                urls = if (url.isNotEmpty()) listOf(url) else emptyList(),
            )
        }
    }

    // Minimal RFC-4180 CSV: quoted fields, "" escapes, commas/newlines inside quotes.
    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i++
                    }

                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }

                c == '"' -> inQuotes = true
                c == ',' -> {
                    row.add(field.toString()); field.setLength(0)
                }

                c == '\r' -> {}
                c == '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    rows.add(ArrayList(row)); row.clear()
                }

                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(ArrayList(row))
        }
        return rows
    }

    // ── Instagram followers_1.json / following.json ─────────────────────
    // followers_1.json is a top-level array of {string_list_data:[{href,value}]};
    // following.json wraps the same array under "relationships_following".
    private fun parseInstagramJson(text: String): List<RawContact> {
        val array = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            val obj = JSONObject(text)
            obj.keys().asSequence()
                .map { obj.opt(it) }
                .filterIsInstance<JSONArray>()
                .firstOrNull() ?: return emptyList()
        }

        val out = ArrayList<RawContact>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val data = entry.optJSONArray("string_list_data") ?: continue
            val item = data.optJSONObject(0) ?: continue
            val username = item.optString("value")
            if (username.isEmpty()) continue

            val href = item.optString("href")
            out.add(
                RawContact(
                    source = "instagram",
                    name = username,
                    urls = if (href.isNotEmpty()) listOf(href) else emptyList(),
                )
            )
        }
        return out
    }
}
