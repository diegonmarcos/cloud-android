package com.diegonmarcos.superapp.contacts

/**
 * One contact as it comes out of a single source, before merging. Every
 * reader in this module (DeviceContacts for ContactsContract, SocialImport
 * for LinkedIn/Instagram exports, SocialStore for whatever was persisted
 * from a previous import) speaks this one shape — MergeEngine is the only
 * thing that ever turns a list of these into [Person]s, so a new source
 * only has to know how to produce RawContacts, never how merging works.
 */
data class RawContact(
    val source: String,                    // "local" | "gmail:<account>" | "<account-type>" | "linkedin" | "instagram"
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val org: String = "",
    val title: String = "",
    val urls: List<String> = emptyList(),
    val handles: Map<String, String> = emptyMap(),   // e.g. "telegram" -> "@user", "matrix" -> "@u:host"
)

/**
 * One merged identity — the unit the switchboard UI actually renders. `id`
 * is derived from member RawContacts (see MergeEngine) so it stays stable
 * across re-imports as long as the same email/phone/name keys still match;
 * do not treat it as a database row id.
 */
data class Person(
    val id: String,
    val name: String,
    val org: String,
    val title: String,
    val sources: List<String>,
    val phones: List<String>,
    val emails: List<String>,
    val urls: List<String>,
    val handles: Map<String, String>,
)
