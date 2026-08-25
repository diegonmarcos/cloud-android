package com.diegonmarcos.superapp.contacts

import java.security.MessageDigest

/**
 * Folds RawContacts from every source (device, Gmail, LinkedIn, Instagram,
 * …) into one [Person] per real-world identity. Two RawContacts are the
 * same person when they share a normalized email, a normalized phone, or a
 * normalized name — see [keysFor]. This is a union-find over those keys,
 * not pairwise comparison: an import can be a few thousand rows, and
 * O(n²) contact matching is exactly the kind of thing that turns "tap
 * import" into a multi-second freeze. Each key (email/phone/name) maps to
 * one union-find root, so two contacts merge in ~O(α(n)) the moment they
 * share any key, however many rows sit between them in the input list.
 *
 * Merge keys are intentionally coarse (bare name matches!) because this is
 * a personal contacts hub fed by exports the user themselves curated, not
 * a general-purpose entity-resolution system — false merges are rare in
 * that setting and a wrongly-split person is more annoying day to day than
 * a wrongly-joined one (fixing a split means hunting for the second card;
 * a bad join is at worst a stray phone number on the right person).
 */
object MergeEngine {

    fun merge(raw: List<RawContact>): List<Person> {
        if (raw.isEmpty()) return emptyList()

        val parent = IntArray(raw.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        val keyToIndex = HashMap<String, Int>()
        raw.forEachIndexed { i, c ->
            for (key in keysFor(c)) {
                val existing = keyToIndex[key]
                if (existing == null) keyToIndex[key] = i else union(i, existing)
            }
        }

        val groups = LinkedHashMap<Int, MutableList<RawContact>>()
        raw.forEachIndexed { i, c -> groups.getOrPut(find(i)) { ArrayList() }.add(c) }

        return groups.values
            .map { buildPerson(it) }
            .sortedBy { it.name.lowercase() }
    }

    /** Normalized email(s), normalized phone(s), and normalized name — the
     *  union-find keys for one RawContact. A blank/empty key never
     *  participates (an empty name must not glue every nameless import
     *  row into one giant person). */
    private fun keysFor(c: RawContact): List<String> {
        val keys = ArrayList<String>()
        c.emails.forEach { e -> normalizeEmail(e)?.let { keys.add("email:$it") } }
        c.phones.forEach { p -> normalizePhone(p)?.let { keys.add("phone:$it") } }
        normalizeName(c.name)?.let { keys.add("name:$it") }
        return keys
    }

    private fun normalizeEmail(e: String): String? =
        e.trim().lowercase().takeIf { it.isNotEmpty() }

    /** Digits only; when there are at least 9 digits, key on the LAST 9 so
     *  "+33 6 12 34 56 78" and "0612345678" (same French mobile, one with
     *  a country code, one with a domestic trunk prefix) collide. Shorter
     *  numbers (extensions, partial entries) are compared exactly instead
     *  — truncating those to "last 9" would make unrelated short numbers
     *  collide instead of matching nothing. */
    private fun normalizePhone(p: String): String? {
        val digits = p.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return if (digits.length >= 9) digits.takeLast(9) else digits
    }

    private fun normalizeName(n: String): String? =
        n.trim().lowercase().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }

    private fun buildPerson(members: List<RawContact>): Person {
        val name = members.map { it.name }.filter { it.isNotBlank() }
            .maxByOrNull { it.length } ?: ""
        val org = members.map { it.org }.firstOrNull { it.isNotBlank() } ?: ""
        val title = members.map { it.title }.firstOrNull { it.isNotBlank() } ?: ""

        val phones = LinkedHashMap<String, String>() // normalized -> first raw spelling
        val emails = LinkedHashMap<String, String>()
        val urls = LinkedHashSet<String>()
        val handles = LinkedHashMap<String, String>()
        val sources = sortedSetOf<String>()

        members.forEach { c ->
            sources.add(c.source)
            c.phones.forEach { p -> normalizePhone(p)?.let { phones.putIfAbsent(it, p) } }
            c.emails.forEach { e -> normalizeEmail(e)?.let { emails.putIfAbsent(it, e) } }
            urls.addAll(c.urls)
            c.handles.forEach { (k, v) -> handles.putIfAbsent(k, v) }
        }

        val idSeed = members.flatMap { keysFor(it) }.toSortedSet().joinToString("|")
        val id = md5Hex(idSeed).take(12)

        return Person(
            id = id,
            name = name,
            org = org,
            title = title,
            sources = sources.toList(),
            phones = phones.values.toList(),
            emails = emails.values.toList(),
            urls = urls.toList(),
            handles = handles,
        )
    }

    private fun md5Hex(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
