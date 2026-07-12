package com.diegonmarcos.superapp.wallet

import android.content.Context
import org.json.JSONObject
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session

/**
 * IMAP mail scanner — scans INBOX for .pkpass / wallet.json attachments
 * and converts them into [WalletStore.Card] entries.
 *
 * Designed for the user's own Maddy/Stalwart stack at imap.diegonmarcos.com:443
 * (IMAPS tunnelled through Caddy L4 SNI — same port as HTTPS).
 * Credentials are stored in private SharedPreferences (not sops; device-local only).
 */
object WalletMailConnector {

    private const val PREFS = "wallet_mail_connector"

    // ── Config ──────────────────────────────────────────────────────────────

    data class Config(
        val host: String  = "imap.diegonmarcos.com",
        val port: Int     = 443,
        val email: String = "",
        val password: String = "",
    )

    fun saveConfig(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host",     cfg.host)
            .putInt(   "port",     cfg.port)
            .putString("email",    cfg.email)
            .putString("password", cfg.password)
            .apply()
    }

    fun loadConfig(ctx: Context): Config {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            host     = sp.getString("host",     "imap.diegonmarcos.com") ?: "imap.diegonmarcos.com",
            port     = sp.getInt("port",        443),
            email    = sp.getString("email",    "") ?: "",
            password = sp.getString("password", "") ?: "",
        )
    }

    // ── Scan ────────────────────────────────────────────────────────────────

    data class ScanResult(
        val cards:    List<WalletStore.Card>,
        val scanned:  Int,
        val errors:   Int,
        val message:  String,
    )

    /**
     * Connect to IMAP, scan the last [maxMessages] messages in INBOX for
     * .pkpass and wallet.json attachments. Call from a background thread
     * (Dispatchers.IO) — blocks until done.
     */
    fun scan(ctx: Context, maxMessages: Int = 200): ScanResult {
        val cfg = loadConfig(ctx)
        require(cfg.email.isNotBlank() && cfg.password.isNotBlank()) {
            "Mail Connector: email and password required"
        }
        val props = Properties().apply {
            put("mail.store.protocol",          "imaps")
            put("mail.imaps.host",              cfg.host)
            put("mail.imaps.port",              cfg.port.toString())
            put("mail.imaps.ssl.enable",        "true")
            put("mail.imaps.ssl.trust",         "*")       // trusts self-signed certs on private servers
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout",           "20000")
        }
        val session = Session.getInstance(props)
        val store   = session.getStore("imaps")
        store.connect(cfg.host, cfg.port, cfg.email, cfg.password)

        return try {
            val inbox = store.getFolder("INBOX").also { it.open(Folder.READ_ONLY) }
            val count = inbox.messageCount
            val start = maxOf(1, count - maxMessages + 1)
            val msgs  = inbox.getMessages(start, count)

            val found  = mutableListOf<WalletStore.Card>()
            var errors = 0

            msgs.forEach { msg ->
                runCatching {
                    val content = msg.content
                    if (content is Multipart) extractFromMultipart(content, found)
                }.onFailure { errors++ }
            }

            inbox.close(false)
            ScanResult(
                cards    = found,
                scanned  = msgs.size,
                errors   = errors,
                message  = "Scanned ${msgs.size} messages · found ${found.size} pass(es)" +
                           if (errors > 0) " · $errors error(s)" else "",
            )
        } finally {
            store.close()
        }
    }

    private fun extractFromMultipart(mp: Multipart, out: MutableList<WalletStore.Card>) {
        for (i in 0 until mp.count) {
            val part = mp.getBodyPart(i)
            val name = part.fileName?.lowercase() ?: ""
            val ct   = (part.contentType ?: "").lowercase()
            when {
                name.endsWith(".pkpass") || ct.contains("pkpass") ->
                    parsePkpass(part.inputStream.readBytes())?.let { out.add(it) }
                name.endsWith(".json") && !name.contains("manifest") ->
                    parseWalletJson(part.inputStream.readBytes().toString(Charsets.UTF_8))
                        .forEach { out.add(it) }
                part.content is Multipart ->
                    extractFromMultipart(part.content as Multipart, out)
            }
        }
    }

    // ── pkpass parsing ───────────────────────────────────────────────────────

    private fun parsePkpass(bytes: ByteArray): WalletStore.Card? = runCatching {
        // pkpass is a ZIP file containing pass.json (+ images, manifest, signature)
        val zip   = ZipInputStream(bytes.inputStream())
        var entry = zip.nextEntry
        var passJson: JSONObject? = null
        while (entry != null) {
            if (entry.name == "pass.json") {
                passJson = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
            }
            entry = zip.nextEntry
        }
        val p      = passJson ?: return null
        val org    = p.optString("organizationName", "")
        val desc   = p.optString("description", "")
        val serial = p.optString("serialNumber", "")
        val accent = parseRgbColor(p.optString("backgroundColor", "")) ?: 0xFF1E293BL

        val passType = listOf("boardingPass", "coupon", "eventTicket", "generic", "storeCard")
            .firstOrNull { p.has(it) } ?: "generic"
        val passObj  = p.optJSONObject(passType)

        // Collect primary field values (e.g. "BER" / "MUC" for boarding passes)
        val primArr    = passObj?.optJSONArray("primaryFields")
        val primValues = (0 until (primArr?.length() ?: 0)).map {
            primArr!!.getJSONObject(it).optString("value")
        }
        val transit = passObj?.optString("transitType", "") ?: ""

        // Map pkpass type → our kind + display strings
        val (kind, brand, tagline) = when (passType) {
            "boardingPass" -> Triple(
                if (transit.contains("Air", ignoreCase = true)) "flight" else "transit",
                org.ifBlank { "Boarding Pass" },
                primValues.joinToString(" → ").ifBlank { desc },
            )
            "eventTicket" -> Triple(
                "music",
                org.ifBlank { "Event Ticket" },
                desc.ifBlank { primValues.firstOrNull() ?: "" },
            )
            "storeCard"   -> Triple("loyalty", org.ifBlank { "Store Card" }, desc)
            "coupon"      -> Triple("coupon",   org.ifBlank { "Coupon" },     desc)
            else          -> Triple("transit",  org.ifBlank { "Pass" },       desc)
        }

        WalletStore.Card(
            id      = if (serial.isNotBlank()) "pkpass_$serial" else UUID.randomUUID().toString(),
            kind    = kind,
            brand   = brand,
            tagline = tagline,
            accent  = accent,
            barcode = serial,
            number  = primValues.getOrNull(0) ?: "",
        )
    }.getOrNull()

    private fun parseWalletJson(text: String): List<WalletStore.Card> = runCatching {
        val root   = JSONObject(text)
        val result = mutableListOf<WalletStore.Card>()
        mapOf("ids" to "id", "docs" to "doc", "passes" to "pass").forEach { (section, cat) ->
            val arr = root.optJSONArray(section) ?: return@forEach
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (!obj.has("category")) obj.put("category", cat)
                result.add(WalletStore.Card.fromJson(obj))
            }
        }
        val cardsArr = root.optJSONArray("cards")
        if (cardsArr != null) for (i in 0 until cardsArr.length()) result.add(WalletStore.Card.fromJson(cardsArr.getJSONObject(i)))
        result
    }.getOrDefault(emptyList())

    // "rgb(33, 44, 88)" → 0xFF21_2C_58L
    private fun parseRgbColor(raw: String): Long? = runCatching {
        val m = Regex("""rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""").find(raw) ?: return null
        val (r, g, b) = m.destructured
        0xFF_00_00_00L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }.getOrNull()
}
