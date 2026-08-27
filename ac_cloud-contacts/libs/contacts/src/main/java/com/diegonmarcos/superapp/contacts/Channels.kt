package com.diegonmarcos.superapp.contacts

import org.json.JSONArray

/** One action a channel can perform (chat/mail/call/…), with its URI
 *  TEMPLATE still containing `{placeholder}`s — see [Channels.forPerson]
 *  for substitution. */
data class ChannelAction(val id: String, val label: String, val uri: String)

/** One row of build.json's `channels.registry` — the whole set of
 *  channels the switchboard can ever show, data-driven so adding a
 *  channel is a JSON edit, never a Kotlin change. */
data class ChannelConfig(
    val id: String,
    val label: String,
    val kind: String,
    val color: String,
    val glyph: String,
    val order: Int,
    val pkg: String?,
    val actions: List<ChannelAction>,
)

/** One channel resolved against a specific [Person]: the datum that made
 *  it available ([value]) plus its actions with placeholders already
 *  substituted into real URIs, ready to hand to the bridge/launcher. */
data class PersonChannel(val config: ChannelConfig, val value: String, val actions: List<ChannelAction>)

/**
 * Turns the registry JSON (baked into BuildConfig.CHANNELS_B64, decoded by
 * the app before it ever reaches here — this object never touches
 * base64) into [ChannelConfig]s, and resolves which channels a given
 * [Person] actually has against that registry. `kind` is the only place
 * that knows how a Person datum maps to a channel; everything else here
 * is generic over whatever `kind`s the registry happens to define.
 */
object Channels {

    fun parse(json: String): List<ChannelConfig> {
        val arr = JSONArray(json)
        val out = ArrayList<ChannelConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val actionsArr = o.optJSONArray("actions")
            val actions = ArrayList<ChannelAction>()
            if (actionsArr != null) {
                for (j in 0 until actionsArr.length()) {
                    val a = actionsArr.optJSONObject(j) ?: continue
                    actions.add(ChannelAction(a.optString("id"), a.optString("label"), a.optString("uri")))
                }
            }
            out.add(
                ChannelConfig(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    kind = o.optString("kind"),
                    color = o.optString("color"),
                    glyph = o.optString("glyph"),
                    order = o.optInt("order"),
                    pkg = if (o.has("package") && !o.isNull("package")) o.optString("package") else null,
                    actions = actions,
                )
            )
        }
        return out
    }

    fun forPerson(person: Person, registry: List<ChannelConfig>): List<PersonChannel> {
        return registry.mapNotNull { config ->
            val value = valueFor(config.kind, person) ?: return@mapNotNull null
            PersonChannel(config, value, config.actions.map { it.copy(uri = substitute(it.uri, config.kind, value)) })
        }.sortedBy { it.config.order }
    }

    private fun valueFor(kind: String, person: Person): String? = when {
        kind == "phone" -> person.phones.firstOrNull()
        kind == "email" -> person.emails.firstOrNull()
        kind.startsWith("url:") -> {
            val domain = kind.removePrefix("url:").lowercase()
            person.urls.firstOrNull { it.lowercase().contains(domain) }
        }

        kind.startsWith("handle:") -> {
            val key = kind.removePrefix("handle:")
            person.handles[key]?.takeIf { it.isNotBlank() }
        }

        else -> null
    }

    /** {handle} strips a leading "@" (t.me-style deep links don't want
     *  it); {handle_raw} keeps the value exactly as stored (matrix.to
     *  wants the "@" — it's part of the Matrix user id) — see build.json's
     *  channels._doc for why this distinction exists. */
    private fun substitute(uriTemplate: String, kind: String, value: String): String {
        var uri = uriTemplate
        when {
            kind == "phone" -> {
                uri = uri.replace("{phone_digits}", value.filter { it.isDigit() })
                uri = uri.replace("{phone}", value)
            }

            kind == "email" -> uri = uri.replace("{email}", value)
            kind.startsWith("url:") -> uri = uri.replace("{url}", value)
            kind.startsWith("handle:") -> {
                uri = uri.replace("{handle_raw}", value)
                uri = uri.replace("{handle}", value.removePrefix("@"))
            }
        }
        return uri
    }
}
