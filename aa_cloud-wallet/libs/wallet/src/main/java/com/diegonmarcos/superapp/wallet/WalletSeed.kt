package com.diegonmarcos.superapp.wallet

import java.util.UUID

/**
 * Mock seed data for [WalletStore]. Extracted so the CRUD logic in
 * WalletStore stays readable without scrolling past ~200 lines of
 * sample cards. Bump [WalletStore.SEED_VERSION] whenever data here changes.
 */
internal fun mockSeed(): List<WalletStore.Card> = listOf(
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "vcard",
        brand   = "Virtual Business Card",
        tagline = "Tap to expand — QR vCard",
        accent  = 0xFF7C3AED,
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "vcard_imported",
        brand   = "Imported vCard",
        tagline = "Tap to open the card details",
        accent  = 0xFF6366F1,
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "credit",
        brand   = "American Express Gold",
        tagline = "Charge · since 2019",
        accent  = 0xFFD4AF37,
        number  = "•••• ••••• 21006",
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "debit",
        brand   = "N26 Debit",
        tagline = "Visa · IBAN ending 4582",
        accent  = 0xFF111827,
        number  = "•••• •••• •••• 4582",
    ),
    // ── Banking cards ──────────────────────────────────────────────────────
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "debit",
        brand   = "Revolut",
        tagline = "Personal · Visa Debit",
        accent  = 0xFF18191A,
        number  = "•••• •••• •••• 9241",
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "credit",
        brand   = "Revolut",
        tagline = "Credit · Visa",
        accent  = 0xFF1A1A2E,
        number  = "•••• •••• •••• 6108",
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "virtual_debit",
        brand   = "Revolut Virtual",
        tagline = "Virtual Debit · Disposable",
        accent  = 0xFF0D0D1A,
        number  = "•••• •••• •••• 3379",
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "debit",
        brand   = "Santander",
        tagline = "Debit · Visa",
        accent  = 0xFFCC0000,
        number  = "•••• •••• •••• 5547",
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "credit",
        brand   = "Santander",
        tagline = "Credit · Mastercard",
        accent  = 0xFFAA0000,
        number  = "•••• •••• •••• 2291",
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "debit",
        brand   = "BTG Pactual",
        tagline = "Debit · Mastercard",
        accent  = 0xFF00539C,
        number  = "•••• •••• •••• 8837",
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "credit",
        brand   = "BTG Pactual",
        tagline = "Credit · Mastercard Gold",
        accent  = 0xFF002B80,
        number  = "•••• •••• •••• 4410",
    ),
    // ── Passes (transit + gym) ─────────────────────────────────────────────
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "transit",
        brand   = "BVG Berlin",
        tagline = "AB · Monatskarte · expires 2026-07-31",
        accent  = 0xFFEAB308,
        barcode = "880088_BVG_DM",
    ),
    WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "gym",
        brand   = "FitX",
        tagline = "Berlin Mitte · since 2024",
        accent  = 0xFF166534,
        number  = "M-77129",
    ),
    // ── TICKETS — event-bound (eventAt > 0) ───────────────────────────────
    WalletStore.Card(
        id            = UUID.randomUUID().toString(),
        kind          = "flight",
        brand         = "Lufthansa",
        tagline       = "LH 192 · BER → MUC",
        accent        = 0xFF1E40AF,
        barcode       = "M1MARCOS/DIEGO       EXXX23 BERMUCLH 0192",
        number        = "Seat 12A · Gate B14",
        eventAt       = relativeMillis(daysFromNow = 2, hour = 14, minute = 35),
        eventLocation = "BER → MUC",
    ),
    WalletStore.Card(
        id            = UUID.randomUUID().toString(),
        kind          = "train",
        brand         = "Deutsche Bahn ICE",
        tagline       = "ICE 1024 · Berlin Hbf → München Hbf",
        accent        = 0xFFE11D2A,
        number        = "Wagen 23 · Sitz 71",
        eventAt       = relativeMillis(daysFromNow = 5, hour = 9, minute = 12),
        eventLocation = "Berlin Hbf",
    ),
    WalletStore.Card(
        id            = UUID.randomUUID().toString(),
        kind          = "music",
        brand         = "Berghain · Klubnacht",
        tagline       = "Friday Klubnacht",
        accent        = 0xFF1F2937,
        number        = "Doors 23:55",
        eventAt       = relativeMillis(daysFromNow = 7, hour = 23, minute = 55),
        eventLocation = "Am Wriezener Bahnhof, Berlin",
    ),
    WalletStore.Card(
        id            = UUID.randomUUID().toString(),
        kind          = "theater",
        brand         = "Berliner Ensemble",
        tagline       = "Die Dreigroschenoper",
        accent        = 0xFF8B0000,
        number        = "Reihe 7 · Sitz 14",
        eventAt       = relativeMillis(daysFromNow = 10, hour = 19, minute = 30),
        eventLocation = "Bertolt-Brecht-Platz 1, Berlin",
    ),
    // ── PAST TICKETS (Archive view) ────────────────────────────────────────
    WalletStore.Card(
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
    WalletStore.Card(
        id            = UUID.randomUUID().toString(),
        kind          = "music",
        brand         = "Funkhaus Berlin",
        tagline       = "Nils Frahm — Solo Piano",
        accent        = 0xFF7E22CE,
        number        = "GA · Doors 20:00",
        eventAt       = relativeMillis(daysFromNow = -14, hour = 20, minute = 0),
        eventLocation = "Nalepastraße 18, Berlin",
    ),
    WalletStore.Card(
        id            = UUID.randomUUID().toString(),
        kind          = "train",
        brand         = "Deutsche Bahn ICE",
        tagline       = "ICE 925 · Köln → Berlin",
        accent        = 0xFFE11D2A,
        number        = "Wagen 7 · Sitz 33",
        eventAt       = relativeMillis(daysFromNow = -28, hour = 7, minute = 47),
        eventLocation = "Köln Hbf",
    ),
    WalletStore.Card(
        id            = UUID.randomUUID().toString(),
        kind          = "theater",
        brand         = "Schaubühne",
        tagline       = "Hamlet — Thomas Ostermeier",
        accent        = 0xFF5B0F0F,
        number        = "Reihe 4 · Sitz 9",
        eventAt       = relativeMillis(daysFromNow = -42, hour = 19, minute = 30),
        eventLocation = "Kurfürstendamm 153, Berlin",
    ),
)

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
