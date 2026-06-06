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
        val raw = sp.getString(KEY, null)
        if (raw == null) {
            val seed = mockSeed()
            saveAll(ctx, seed)
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

    fun remove(ctx: Context, id: String) {
        val current = all(ctx).toMutableList()
        current.removeAll { it.id == id }
        saveAll(ctx, current)
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /** Seeded sample cards covering each `kind` so the deck has visual
     *  variety on first launch. Edit via the upcoming import path or
     *  by tapping a card to remove. */
    private fun mockSeed(): List<Card> = listOf(
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "music",
            brand   = "TIDAL",
            tagline = "Premium · since 2018",
            accent  = 0xFF111827L, // near-black
            number  = "DM-771249",
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "transit",
            brand   = "BVG Berlin",
            tagline = "AB · Monatskarte",
            accent  = 0xFFEAB308L, // amber
            barcode = "880088_BVG_DM",
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "boarding",
            brand   = "Lufthansa",
            tagline = "LH 192 · BER → MUC · 14:35",
            accent  = 0xFF1E40AFL, // royal blue
            barcode = "M1MARCOS/DIEGO       EXXX23 BERMUCLH 0192",
            number  = "Seat 12A · Gate B14",
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "loyalty",
            brand   = "Carrefour Pass",
            tagline = "Loyalty · gold tier",
            accent  = 0xFFB91C1CL, // carrefour red
            number  = "C-3247 8821 0098",
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "membership",
            brand   = "FitX",
            tagline = "Berlin Mitte · since 2024",
            accent  = 0xFF166534L, // forest green
            number  = "M-77129",
        ),
        Card(
            id      = UUID.randomUUID().toString(),
            kind    = "totp",
            brand   = "GitHub · TOTP",
            tagline = "Preview — full vault in Vaultwarden",
            accent  = 0xFF581C87L, // deep purple
            number  = "184 902",
        ),
    )
}
