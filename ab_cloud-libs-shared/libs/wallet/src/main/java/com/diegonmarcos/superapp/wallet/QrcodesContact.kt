package com.diegonmarcos.superapp.wallet

import android.content.Context
import org.json.JSONObject

/**
 * Canonical vCard contact, parsed from the bundled
 * `assets/qrcodes/qrcodes.json::contact` block. That file's own
 * description names itself the single source of truth ("NO vCard data
 * lives anywhere else; the .vcf files under src/public/ are generated
 * artifacts"), so the in-app Wallet vcard card and any future surface
 * that wants to mirror the QR-codes payload should read from here
 * rather than from [ProfilePrefs] (the editable settings layer).
 */
data class QrcodesContact(
    val displayName: String,
    val email:       String,
    val tel:         String,
    val street:      String,
    val city:        String,
    val region:      String,
    val country:     String,
    val landingUrl:  String,
    val linktreeUrl: String,
    val telegramUrl: String,
    val whatsappUrl: String,
    val socials:     String,
    val birthday:    String,
) {
    /** "City, Country" with empty-side trimming. */
    val cityCountry: String get() = listOf(city, country)
        .filter { it.isNotBlank() }
        .joinToString(", ")

    /** Multi-line full identity for the expanded-card view. Matches the
     *  field set the generated .vcf file ships (tel, email, address,
     *  every URL with its role, birthday, socials handle). Lines omit
     *  when their underlying field is blank so an unfilled contact
     *  doesn't print a row of empty bullets. */
    fun fullCardTagline(): String {
        val addr = listOf(street, cityCountry, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        return listOf(
            tel,
            email,
            addr,
            linktreeUrl.removePrefix("https://"),
            telegramUrl.removePrefix("https://"),
            whatsappUrl.removePrefix("https://"),
            landingUrl.removePrefix("https://"),
            if (birthday.isNotBlank()) "BDAY $birthday" else "",
            if (socials.isNotBlank())  "Socials $socials" else "",
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
}

object QrcodesData {

    @Volatile private var cached: QrcodesContact? = null

    /** Parse + cache the contact block. Returns null only when the
     *  asset is missing or malformed — every install ships qrcodes.json
     *  via the assets/ folder, so null at runtime indicates a build
     *  packaging problem, not a user-reachable state. */
    fun contact(ctx: Context): QrcodesContact? {
        cached?.let { return it }
        return runCatching {
            val text = ctx.applicationContext.assets
                .open("qrcodes/qrcodes.json")
                .bufferedReader()
                .use { it.readText() }
            val root    = JSONObject(text)
            val contact = root.getJSONObject("contact")
            val links   = root.optJSONObject("links") ?: JSONObject()
            val addr    = contact.optJSONObject("address") ?: JSONObject()

            fun linkValue(id: String): String =
                links.optJSONObject(id)?.optString("value").orEmpty()

            val parsed = QrcodesContact(
                displayName = contact.optString("displayName")
                    .ifBlank { (contact.optString("given") + " " + contact.optString("family")).trim() },
                email       = contact.optString("email"),
                tel         = contact.optString("tel"),
                street      = addr.optString("street"),
                city        = addr.optString("city"),
                region      = addr.optString("region"),
                country     = addr.optString("country"),
                landingUrl  = linkValue("landpage"),
                linktreeUrl = linkValue("linktree"),
                telegramUrl = linkValue("telegram"),
                whatsappUrl = linkValue("whatsapp"),
                socials     = linkValue("socials"),
                birthday    = contact.optString("birthday"),
            )
            cached = parsed
            parsed
        }.getOrNull()
    }
}
