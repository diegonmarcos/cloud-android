package com.diegonmarcos.superapp.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * SharedPreferences-backed wallet — list of cards (loyalty / membership /
 * transit / boarding / TOTP preview) rendered by the Compose WalletDeck.
 *
 * Mock seed populates on first read so the surface has something to
 * show before the user imports anything. Real import paths (.pkpass,
 * paste-config, etc.) wire on top of [push].
 */
object WalletStore {
    private const val PREFS = "wallet_store"
    private const val KEY   = "cards"
    /** Bump when the mock seed contents change so dev/test installs
     *  automatically reseed on next open. Real user-added cards are
     *  the casualty here, but during mock-data phase nobody's adding
     *  cards yet — the import path lands later. */
    private const val SEED_VERSION = 7
    private const val SEED_VERSION_KEY = "seed_version"

    /** A single card. Visual variants picked by [kind]; the deck
     *  composable maps kind → background + accent colour.
     *
     *  [eventAt] partitions the wallet into the two top-level surfaces:
     *  Cards (eventAt == 0L → long-lasting credentials like a debit
     *  card, transit pass, gym membership) and Tickets (eventAt > 0L
     *  → event-bound passes — flights, trains, concerts, theatre).
     *  The Calendar tab is just the Tickets list, regrouped by date. */
    data class Card(
        val id: String,
        val kind: String,        // "vcard" | "vcard_imported" | "credit" | "debit" | "transit" | "gym" | "flight" | "train" | "music" | "theater" | "id_*" | "doc_*" | …
        val brand: String,       // top-line label (e.g. "TIDAL")
        val tagline: String,     // sub-line (e.g. "Premium · since 2018")
        val accent: Long,        // ARGB int (e.g. 0xFF111827) — packed into Long so signed ints survive JSON
        val barcode: String = "",
        val number: String = "", // visible identifier, e.g. card number, member id
        val eventAt: Long = 0L,  // epoch millis of the ticket event; 0 = no event (long-lasting card)
        val eventLocation: String = "", // venue / origin → destination, free-form
        val category: String = "", // "" | "card" | "id" | "doc"  — drives tab routing
        val country: String = "",  // "es" | "br" | "" — drives IDs tab country filter
    ) {
        /** Convenience — distinguishes Cards from Tickets without
         *  having to read [eventAt] directly at every call site. */
        val isTicket: Boolean get() = eventAt > 0L

        /** True for tickets whose event time has passed. Drives the
         *  Archive view inside the Tickets tab — upcoming tickets stay
         *  in the main list, past ones automatically move to Archive. */
        val isPastTicket: Boolean get() = isTicket && eventAt < System.currentTimeMillis()

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("brand", brand)
            put("tagline", tagline)
            put("accent", accent)
            put("barcode", barcode)
            put("number", number)
            put("eventAt", eventAt)
            put("eventLocation", eventLocation)
            put("category", category)
            put("country", country)
        }
        companion object {
            fun fromJson(o: JSONObject): Card = Card(
                id            = o.optString("id", UUID.randomUUID().toString()),
                kind          = o.optString("kind", "loyalty"),
                brand         = o.optString("brand", ""),
                tagline       = o.optString("tagline", ""),
                accent        = o.optLong("accent", 0xFF111827L),
                barcode       = o.optString("barcode", ""),
                number        = o.optString("number", ""),
                eventAt       = o.optLong("eventAt", 0L),
                eventLocation = o.optString("eventLocation", ""),
                category      = o.optString("category", ""),
                country       = o.optString("country", ""),
            )
        }
    }

    /** Newest-first list of cards. On first read returns the mock seed
     *  AND writes it to prefs so subsequent calls round-trip through
     *  the same JSON. IDs and Docs are loaded from assets/wallet.json
     *  and appended to the regular card/ticket seed. */
    fun all(ctx: Context): List<Card> {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw            = sp.getString(KEY, null)
        val storedVersion  = sp.getInt(SEED_VERSION_KEY, 0)
        if (raw == null || storedVersion != SEED_VERSION) {
            val seed = mockSeed() + loadWalletJson(ctx)
            saveAll(ctx, seed)
            sp.edit().putInt(SEED_VERSION_KEY, SEED_VERSION).apply()
            return seed
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Card.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Load IDs and Docs from assets/wallet.json. Safe — returns empty
     *  list if file is absent or malformed. */
    private fun loadWalletJson(ctx: Context): List<Card> = runCatching {
        val text = ctx.assets.open("wallet.json").bufferedReader().readText()
        val root = JSONObject(text)
        val result = mutableListOf<Card>()
        listOf("ids", "docs").forEach { section ->
            val arr = root.optJSONArray(section) ?: return@forEach
            for (i in 0 until arr.length()) result.add(Card.fromJson(arr.getJSONObject(i)))
        }
        result
    }.getOrDefault(emptyList())

    fun saveAll(ctx: Context, cards: List<Card>) {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (c in cards) arr.put(c.toJson())
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun push(ctx: Context, card: Card) {
        val current = all(ctx).toMutableList()
        current.add(0, card)
        saveAll(ctx, current)
    }

    /** Replace an existing card in place (matched by id). No-op if the id
     *  is unknown — keeps the deck immutable from the caller's POV. Used
     *  by the per-card config screen to persist edits. */
    fun update(ctx: Context, card: Card) {
        val current = all(ctx).toMutableList()
        val idx = current.indexOfFirst { it.id == card.id }
        if (idx < 0) return
        current[idx] = card
        saveAll(ctx, current)
    }

    fun remove(ctx: Context, id: String) {
        val current = all(ctx).toMutableList()
        current.removeAll { it.id == id }
        saveAll(ctx, current)
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /** Seeded sample cards — one per user-requested category so the
     *  deck shows the full variety on first launch. The vCard pulls
     *  its brand / tagline / number from ProfilePrefs at render time
     *  (kind == "vcard") so it always reflects the user's current
     *  identity rather than stale snapshot fields. */
    private fun mockSeed(): List<Card> = listOf(
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "vcard",
            brand   = "Virtual Business Card",   // overridden at render via ProfilePrefs
            tagline = "Tap to expand — QR vCard",
            accent  = 0xFF7C3AED,                // brand purple
        ),
        // Imported-vCard EXAMPLE — kind="vcard_imported" is overlaid
        // at render time with the same QrcodesData.contact block the
        // profile card above uses, so the user sees THEIR OWN data in
        // both spots (one acts as the BusinessCardFragment shortcut,
        // the other demonstrates the "this is what an imported .vcf
        // looks like" flow with Selected/Full behaviour). The seeded
        // brand/tagline/number values are placeholders — replaced by
        // QrcodesData on every render. Indigo accent so it's visually
        // distinguishable from the purple profile card.
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "vcard_imported",
            brand   = "Imported vCard",
            tagline = "Tap to open the card details",
            accent  = 0xFF6366F1,                // indigo
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "credit",
            brand   = "American Express Gold",
            tagline = "Charge · since 2019",
            accent  = 0xFFD4AF37,                // gold
            number  = "•••• ••••• 21006",
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "debit",
            brand   = "N26 Debit",
            tagline = "Visa · IBAN ending 4582",
            accent  = 0xFF111827,                // near-black
            number  = "•••• •••• •••• 4582",
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "transit",
            brand   = "BVG Berlin",
            tagline = "AB · Monatskarte · expires 2026-07-31",
            accent  = 0xFFEAB308,                // amber
            barcode = "880088_BVG_DM",
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "gym",
            brand   = "FitX",
            tagline = "Berlin Mitte · since 2024",
            accent  = 0xFF166534,                // forest green
            number  = "M-77129",
        ),
        // ── TICKETS — event-bound (eventAt > 0) ───────────────────
        // Flight (the old "boarding" Lufthansa, now classified as a
        // ticket with a real departure time so it sorts on the
        // Calendar tab). Other tickets follow the same shape:
        // brand = vendor, tagline = route/show, number = seat info,
        // eventLocation = origin → destination or venue address.
        Card(
            id            = UUID.randomUUID().toString(),
            kind          = "flight",
            brand         = "Lufthansa",
            tagline       = "LH 192 · BER → MUC",
            accent        = 0xFF1E40AF,         // royal blue
            barcode       = "M1MARCOS/DIEGO       EXXX23 BERMUCLH 0192",
            number        = "Seat 12A · Gate B14",
            eventAt       = relativeMillis(daysFromNow = 2, hour = 14, minute = 35),
            eventLocation = "BER → MUC",
        ),
        Card(
            id            = UUID.randomUUID().toString(),
            kind          = "train",
            brand         = "Deutsche Bahn ICE",
            tagline       = "ICE 1024 · Berlin Hbf → München Hbf",
            accent        = 0xFFE11D2A,         // DB red
            number        = "Wagen 23 · Sitz 71",
            eventAt       = relativeMillis(daysFromNow = 5, hour = 9, minute = 12),
            eventLocation = "Berlin Hbf",
        ),
        Card(
            id            = UUID.randomUUID().toString(),
            kind          = "music",
            brand         = "Berghain · Klubnacht",
            tagline       = "Friday Klubnacht",
            accent        = 0xFF1F2937,         // obsidian
            number        = "Doors 23:55",
            eventAt       = relativeMillis(daysFromNow = 7, hour = 23, minute = 55),
            eventLocation = "Am Wriezener Bahnhof, Berlin",
        ),
        Card(
            id            = UUID.randomUUID().toString(),
            kind          = "theater",
            brand         = "Berliner Ensemble",
            tagline       = "Die Dreigroschenoper",
            accent        = 0xFF8B0000,         // bordeaux
            number        = "Reihe 7 · Sitz 14",
            eventAt       = relativeMillis(daysFromNow = 10, hour = 19, minute = 30),
            eventLocation = "Bertolt-Brecht-Platz 1, Berlin",
        ),
        // ── PAST TICKETS (Archive view) — same kinds, days < 0 ───
        // Tickets whose eventAt is in the past auto-route into the
        // Tickets tab's Archive view. Built deterministically against
        // install-time so the archive always has something to show.
        Card(
            id            = UUID.randomUUID().toString(),
            kind          = "flight",
            brand         = "Lufthansa",
            tagline       = "LH 187 · MUC → BER",
            accent        = 0xFF1E40AF,
            barcode       = "M1MARCOS/DIEGO       OPQR45 MUCBERLH 0187",
            number        = "Seat 8C · Gate H22",
            eventAt       = relativeMillis(daysFromNow = -3, hour = 18, minute = 50),
            eventLocation = "MUC → BER",
        ),
        Card(
            id            = UUID.randomUUID().toString(),
            kind          = "music",
            brand         = "Funkhaus Berlin",
            tagline       = "Nils Frahm — Solo Piano",
            accent        = 0xFF7E22CE,         // violet
            number        = "GA · Doors 20:00",
            eventAt       = relativeMillis(daysFromNow = -14, hour = 20, minute = 0),
            eventLocation = "Nalepastraße 18, Berlin",
        ),
        Card(
            id            = UUID.randomUUID().toString(),
            kind          = "train",
            brand         = "Deutsche Bahn ICE",
            tagline       = "ICE 925 · Köln → Berlin",
            accent        = 0xFFE11D2A,
            number        = "Wagen 7 · Sitz 33",
            eventAt       = relativeMillis(daysFromNow = -28, hour = 7, minute = 47),
            eventLocation = "Köln Hbf",
        ),
        Card(
            id            = UUID.randomUUID().toString(),
            kind          = "theater",
            brand         = "Schaubühne",
            tagline       = "Hamlet — Thomas Ostermeier",
            accent        = 0xFF5B0F0F,         // dark bordeaux
            number        = "Reihe 4 · Sitz 9",
            eventAt       = relativeMillis(daysFromNow = -42, hour = 19, minute = 30),
            eventLocation = "Kurfürstendamm 153, Berlin",
        ),
    )

    /** Epoch millis at (today + daysFromNow) clock-set to hour:minute.
     *  Used by [mockSeed] so the ticket dates are always in the near
     *  future relative to install time — the Calendar agenda has fresh
     *  rows on every reseed. */
    private fun relativeMillis(daysFromNow: Int, hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, daysFromNow)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
