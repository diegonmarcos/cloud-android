package com.diegonmarcos.superapp.contacts

import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Website

/**
 * Reads the device's own address book — local storage plus whatever
 * accounts (Gmail, work SSO, etc.) are synced into ContactsContract — as
 * [RawContact]s. Deliberately ONE query over the generic Data table rather
 * than one query per mimetype-specific CommonDataKinds URI: those are the
 * same underlying rows with a narrower `WHERE mimetype=...`, and running
 * five of them (name/phone/email/org/website/im) against a contacts list
 * that can be in the thousands is the kind of thing that makes a contacts
 * app visibly stutter on first launch. One cursor, grouped by CONTACT_ID
 * in memory, costs the same I/O as the smallest of those five queries.
 *
 * Never throws for a permission problem: READ_CONTACTS is a dangerous
 * permission the user can revoke at any time (including mid-session on
 * newer Android), and a hub that aggregates several sources must not let
 * one of them take the whole merge down — see MergeEngine, which is happy
 * to run over social-only data if this returns nothing.
 */
object DeviceContacts {

    private class Group {
        var name: String = ""
        val phones = LinkedHashSet<String>()
        val emails = LinkedHashSet<String>()
        var org: String = ""
        var title: String = ""
        val urls = LinkedHashSet<String>()
        val handles = LinkedHashMap<String, String>()
        var accountType: String? = null
        var accountName: String? = null
        var accountSeen = false
    }

    fun read(context: Context): List<RawContact> {
        val groups = LinkedHashMap<Long, Group>()
        try {
            val projection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5,
                ContactsContract.Data.DATA6,
            )
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, projection, null, null, null
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val idxName = c.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME_PRIMARY)
                val idxMime = c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                val idxAccType = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
                val idxAccName = c.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_NAME)
                val idxD1 = c.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                val idxD4 = c.getColumnIndexOrThrow(ContactsContract.Data.DATA4)
                val idxD5 = c.getColumnIndexOrThrow(ContactsContract.Data.DATA5)
                val idxD6 = c.getColumnIndexOrThrow(ContactsContract.Data.DATA6)

                while (c.moveToNext()) {
                    val id = c.getLong(idxId)
                    val g = groups.getOrPut(id) { Group() }

                    if (g.name.isEmpty()) c.getString(idxName)?.let { g.name = it }
                    if (!g.accountSeen) {
                        g.accountType = c.getString(idxAccType)
                        g.accountName = c.getString(idxAccName)
                        g.accountSeen = true
                    }

                    when (c.getString(idxMime)) {
                        Phone.CONTENT_ITEM_TYPE ->
                            c.getString(idxD1)?.takeIf { it.isNotBlank() }?.let { g.phones.add(it) }

                        Email.CONTENT_ITEM_TYPE ->
                            c.getString(idxD1)?.takeIf { it.isNotBlank() }?.let { g.emails.add(it) }

                        Organization.CONTENT_ITEM_TYPE -> {
                            if (g.org.isEmpty()) c.getString(idxD1)?.let { if (it.isNotBlank()) g.org = it }
                            if (g.title.isEmpty()) c.getString(idxD4)?.let { if (it.isNotBlank()) g.title = it }
                        }

                        Website.CONTENT_ITEM_TYPE ->
                            c.getString(idxD1)?.takeIf { it.isNotBlank() }?.let { g.urls.add(it) }

                        Im.CONTENT_ITEM_TYPE -> {
                            val handle = c.getString(idxD1)
                            if (!handle.isNullOrBlank()) {
                                val protocol = if (c.isNull(idxD5)) null else c.getInt(idxD5)
                                val key = if (protocol == Im.PROTOCOL_CUSTOM) {
                                    c.getString(idxD6)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                                } else {
                                    protocolName(protocol)
                                }
                                if (key != null) g.handles[key] = handle
                            }
                        }
                        // StructuredName is intentionally skipped — DISPLAY_NAME_PRIMARY already
                        // gives the formatted full name without reassembling given/family parts.
                    }
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }

        return groups.values.map { g ->
            RawContact(
                source = sourceFor(g.accountType, g.accountName),
                name = g.name,
                phones = g.phones.toList(),
                emails = g.emails.toList(),
                org = g.org,
                title = g.title,
                urls = g.urls.toList(),
                handles = g.handles,
            )
        }
    }

    private fun sourceFor(accountType: String?, accountName: String?): String = when {
        accountType.isNullOrBlank() -> "local"
        accountType == "com.google" -> "gmail:${accountName.orEmpty()}"
        else -> accountType
    }

    private fun protocolName(protocol: Int?): String? = when (protocol) {
        Im.PROTOCOL_AIM -> "aim"
        Im.PROTOCOL_MSN -> "msn"
        Im.PROTOCOL_YAHOO -> "yahoo"
        Im.PROTOCOL_SKYPE -> "skype"
        Im.PROTOCOL_QQ -> "qq"
        Im.PROTOCOL_GOOGLE_TALK -> "google_talk"
        Im.PROTOCOL_ICQ -> "icq"
        Im.PROTOCOL_JABBER -> "jabber"
        Im.PROTOCOL_NETMEETING -> "netmeeting"
        else -> null
    }
}
