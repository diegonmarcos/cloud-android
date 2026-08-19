package org.fossify.phone.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val CALL_PROVIDERS_ASSET = "call_providers.json"

@Serializable
data class CallProvider(
    val id: String,
    val label: String,
    val packageName: String,
    val voipMimetype: String,
    val deepLink: String? = null,
)

enum class OptionKind { VOIP, DEEPLINK }

data class ProviderOption(
    val provider: CallProvider,
    val kind: OptionKind,
    val dataId: Long? = null,
)

private val callProvidersJson = Json { ignoreUnknownKeys = true }

fun parseProviders(raw: String): List<CallProvider> = callProvidersJson.decodeFromString(raw)

fun loadCallProviders(context: Context): List<CallProvider> =
    parseProviders(context.assets.open(CALL_PROVIDERS_ASSET).bufferedReader().use { it.readText() })

/**
 * Pure decision logic (unit-tested). Given the configured providers, which packages are
 * installed, and which providers have a per-contact VoIP data row for the dialed number,
 * return the extra call options to offer alongside plain cellular.
 *
 * A VoIP data row wins (places a real in-app call); otherwise a deep-link option if the app
 * is installed and the provider declares a deepLink; otherwise the provider is omitted.
 */
fun availableOptions(
    providers: List<CallProvider>,
    installedPackages: Set<String>,
    voipDataIds: Map<String, Long?>,
): List<ProviderOption> = providers.mapNotNull { p ->
    val dataId = voipDataIds[p.id]
    when {
        dataId != null -> ProviderOption(p, OptionKind.VOIP, dataId)
        p.deepLink != null && p.packageName in installedPackages -> ProviderOption(p, OptionKind.DEEPLINK)
        else -> null
    }
}

/** Digits-only form for deep links (drops '+', spaces, dashes). */
fun normalizeNumberForDeepLink(number: String): String = number.filter { it.isDigit() }

fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

/**
 * Query the contacts DB for VoIP call data-rows matching the dialed [number], one entry per
 * provider id (null when absent). Requires READ_CONTACTS; returns all-null on SecurityException.
 */
fun queryVoipDataIds(context: Context, number: String, providers: List<CallProvider>): Map<String, Long?> {
    val result = providers.associate { it.id to null as Long? }.toMutableMap()
    val contactId = lookupContactId(context, number) ?: return result
    val byMimetype = providers.associateBy { it.voipMimetype }
    try {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val mimeIdx = c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            while (c.moveToNext()) {
                val provider = byMimetype[c.getString(mimeIdx)] ?: continue
                result[provider.id] = c.getLong(idIdx)
            }
        }
    } catch (e: SecurityException) {
        // no READ_CONTACTS — leave all null
    }
    return result
}

private fun lookupContactId(context: Context, number: String): Long? = try {
    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.CONTACT_ID), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
} catch (e: SecurityException) {
    null
}
