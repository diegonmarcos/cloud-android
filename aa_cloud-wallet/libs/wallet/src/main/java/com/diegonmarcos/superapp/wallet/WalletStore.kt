package com.diegonmarcos.superapp.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    private const val SEED_VERSION = 8
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

    /** Load IDs, Docs and Passes from assets/wallet.json. Safe — returns empty
     *  list if file is absent or malformed. */
    private fun loadWalletJson(ctx: Context): List<Card> = runCatching {
        val text = ctx.assets.open("wallet.json").bufferedReader().readText()
        parseWalletJsonText(text)
    }.getOrDefault(emptyList())

    /** Parse a wallet JSON string. Handles ids / docs / passes / cards sections.
     *  Each section may omit the "category" field — it is inferred from the key. */
    private fun parseWalletJsonText(text: String): List<Card> {
        val root   = JSONObject(text)
        val result = mutableListOf<Card>()
        mapOf("ids" to "id", "docs" to "doc", "passes" to "pass").forEach { (section, defaultCat) ->
            val arr = root.optJSONArray(section) ?: return@forEach
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (!obj.has("category")) obj.put("category", defaultCat)
                result.add(Card.fromJson(obj))
            }
        }
        // "cards" section: raw cards with no forced category
        val cardsArr = root.optJSONArray("cards")
        if (cardsArr != null) {
            for (i in 0 until cardsArr.length()) result.add(Card.fromJson(cardsArr.getJSONObject(i)))
        }
        return result
    }

    /** Import from a user-supplied wallet JSON. Replaces all categorized
     *  cards (ids/docs/passes) while keeping uncategorized cards (banking,
     *  vcard, tickets) and user-added uncategorized cards intact. */
    fun importFromJson(ctx: Context, text: String): String = runCatching {
        val imported = parseWalletJsonText(text)
        val existing = all(ctx).filter { it.category.isEmpty() }
        saveAll(ctx, existing + imported)
        "Imported ${imported.size} item(s) ✓"
    }.getOrElse { "Error: ${it.message}" }

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

}
