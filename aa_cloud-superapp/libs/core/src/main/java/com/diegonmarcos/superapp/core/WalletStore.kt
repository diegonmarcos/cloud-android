package com.diegonmarcos.superapp.core

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
    private const val SEED_VERSION = 2
    private const val SEED_VERSION_KEY = "seed_version"

    /** A single card. Visual variants picked by [kind]; the deck
     *  composable maps kind → background + accent colour. */
    data class Card(
        val id: String,
        val kind: String,        // "loyalty" | "transit" | "boarding" | "totp" | "membership" | "music"
        val brand: String,       // top-line label (e.g. "TIDAL")
        val tagline: String,     // sub-line (e.g. "Premium · since 2018")
        val accent: Long,        // ARGB int (e.g. 0xFF111827) — packed into Long so signed ints survive JSON
        val barcode: String = "",
        val number: String = "", // visible identifier, e.g. card number, member id
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("brand", brand)
            put("tagline", tagline)
            put("accent", accent)
            put("barcode", barcode)
            put("number", number)
        }
        companion object {
            fun fromJson(o: JSONObject): Card = Card(
                id      = o.optString("id", UUID.randomUUID().toString()),
                kind    = o.optString("kind", "loyalty"),
                brand   = o.optString("brand", ""),
                tagline = o.optString("tagline", ""),
                accent  = o.optLong("accent", 0xFF111827L),
                barcode = o.optString("barcode", ""),
                number  = o.optString("number", ""),
            )
        }
    }

    /** Newest-first list of cards. On first read returns the mock seed
     *  AND writes it to prefs so subsequent calls round-trip through
     *  the same JSON. */
    fun all(ctx: Context): List<Card> {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw            = sp.getString(KEY, null)
        val storedVersion  = sp.getInt(SEED_VERSION_KEY, 0)
        if (raw == null || storedVersion != SEED_VERSION) {
            val seed = mockSeed()
            saveAll(ctx, seed)
            sp.edit().putInt(SEED_VERSION_KEY, SEED_VERSION).apply()
            return seed
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Card.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

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
            kind    = "boarding",
            brand   = "Lufthansa",
            tagline = "LH 192 · BER → MUC · 14:35",
            accent  = 0xFF1E40AF,                // royal blue
            barcode = "M1MARCOS/DIEGO       EXXX23 BERMUCLH 0192",
            number  = "Seat 12A · Gate B14",
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
    )
}
