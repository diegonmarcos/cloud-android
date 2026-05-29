package com.diegonmarcos.superapp.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Minimal JMAP (RFC 8620/8621) client.
 *   Slice A — session discovery
 *   Slice B — Mailbox/get
 * Credentials carried on the instance; reuse across calls.
 */
class JmapClient(
    private val server: String,
    email: String,
    password: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val authHeader = "Basic " +
        Base64.getEncoder().encodeToString("$email:$password".toByteArray())

    // ── Slice A ────────────────────────────────────────────────────────

    data class Session(
        val apiUrl: String,
        val downloadUrl: String,
        val uploadUrl: String,
        val eventSourceUrl: String,
        val primaryAccountId: String,
        val accounts: Map<String, String>,
        val raw: JsonElement,
    )

    sealed class Result<T> {
        data class Success<T>(val value: T) : Result<T>()
        data class HttpError<T>(val code: Int, val body: String) : Result<T>()
        data class NetworkError<T>(val message: String) : Result<T>()
    }

    fun discover(): Result<Session> = wrap {
        val body = httpGet("${server.trimEnd('/')}/.well-known/jmap")
        val tree = json.parseToJsonElement(body).jsonObject
        val primary = tree["primaryAccounts"]?.jsonObject
            ?.get("urn:ietf:params:jmap:mail")?.jsonPrimitive?.content
            ?: throw HttpException(200, "no primaryAccounts.mail in session JSON")
        val accountsObj = tree["accounts"]?.jsonObject ?: emptyMap<String, JsonElement>()
        val accounts = accountsObj.mapValues { (_, v) ->
            v.jsonObject["name"]?.jsonPrimitive?.content ?: "(unnamed)"
        }
        Session(
            apiUrl          = tree["apiUrl"]?.jsonPrimitive?.content ?: "",
            downloadUrl     = tree["downloadUrl"]?.jsonPrimitive?.content ?: "",
            uploadUrl       = tree["uploadUrl"]?.jsonPrimitive?.content ?: "",
            eventSourceUrl  = tree["eventSourceUrl"]?.jsonPrimitive?.content ?: "",
            primaryAccountId = primary,
            accounts        = accounts,
            raw             = tree,
        )
    }

    // ── Slice B ────────────────────────────────────────────────────────

    data class Mailbox(
        val id: String,
        val name: String,
        val parentId: String?,
        val role: String?,
        val sortOrder: Int,
        val totalEmails: Int,
        val unreadEmails: Int,
    )

    fun mailboxes(session: Session, accountId: String = session.primaryAccountId): Result<List<Mailbox>> = wrap {
        val payload = buildJsonObject {
            put("using", buildJsonArray {
                add("urn:ietf:params:jmap:core")
                add("urn:ietf:params:jmap:mail")
            })
            put("methodCalls", buildJsonArray {
                addJsonArray {
                    add("Mailbox/get")
                    add(buildJsonObject {
                        put("accountId", accountId)
                    })
                    add("c0")
                }
            })
        }
        val body = httpPost(session.apiUrl, payload.toString())
        val mr = json.parseToJsonElement(body).jsonObject["methodResponses"]?.jsonArray
            ?: throw HttpException(200, "no methodResponses in body")
        val first = mr.firstOrNull()?.jsonArray ?: throw HttpException(200, "empty methodResponses")
        val listEl = first.getOrNull(1)?.jsonObject?.get("list")?.jsonArray
            ?: throw HttpException(200, "no .list in Mailbox/get response")
        listEl.map { it.jsonObject.toMailbox() }
            .sortedWith(compareBy({ it.parentId ?: "" }, { it.sortOrder }, { it.name }))
    }

    private fun JsonObject.toMailbox(): Mailbox = Mailbox(
        id           = this["id"]!!.jsonPrimitive.content,
        name         = this["name"]?.jsonPrimitive?.content ?: "(unnamed)",
        parentId     = this["parentId"]?.jsonPrimitive?.let { if (it.content == "null") null else it.content },
        role         = this["role"]?.jsonPrimitive?.let { if (it.content == "null") null else it.content },
        sortOrder    = this["sortOrder"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        totalEmails  = this["totalEmails"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        unreadEmails = this["unreadEmails"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
    )

    // ── Slice C ────────────────────────────────────────────────────────

    data class Email(
        val id: String,
        val subject: String,
        val from: String,
        val preview: String,
        val receivedAt: String,    // ISO-8601 UTC, raw from JMAP
        val hasAttachment: Boolean,
    )

    /**
     * Email/query → Email/get back-reference, single round-trip. Returns the
     * most recent [limit] messages in [mailboxId] ordered by receivedAt desc.
     * Uses RFC 8620 §3.7 "back-references" (`#ids` resultOf clause) so we
     * pay one HTTP request for both calls.
     */
    fun emails(
        session: Session,
        mailboxId: String,
        limit: Int = 50,
        accountId: String = session.primaryAccountId,
    ): Result<List<Email>> = wrap {
        val payload = buildJsonObject {
            put("using", buildJsonArray {
                add("urn:ietf:params:jmap:core")
                add("urn:ietf:params:jmap:mail")
            })
            put("methodCalls", buildJsonArray {
                addJsonArray {
                    add("Email/query")
                    add(buildJsonObject {
                        put("accountId", accountId)
                        put("filter", buildJsonObject { put("inMailbox", mailboxId) })
                        put("sort", buildJsonArray {
                            addJsonObject {
                                put("property", "receivedAt")
                                put("isAscending", false)
                            }
                        })
                        put("limit", limit)
                    })
                    add("q0")
                }
                addJsonArray {
                    add("Email/get")
                    add(buildJsonObject {
                        put("accountId", accountId)
                        put("#ids", buildJsonObject {
                            put("resultOf", "q0")
                            put("name", "Email/query")
                            put("path", "/ids")
                        })
                        put("properties", buildJsonArray {
                            add("id"); add("subject"); add("from")
                            add("preview"); add("receivedAt"); add("hasAttachment")
                        })
                    })
                    add("g0")
                }
            })
        }

        val body = httpPost(session.apiUrl, payload.toString())
        val responses = json.parseToJsonElement(body).jsonObject["methodResponses"]?.jsonArray
            ?: throw HttpException(200, "no methodResponses")

        // Pick the Email/get response — order is guaranteed by spec but be
        // defensive: find by name.
        val getResp = responses.firstOrNull { resp ->
            resp.jsonArray.firstOrNull()?.jsonPrimitive?.content == "Email/get"
        }?.jsonArray
            ?: throw HttpException(200, "no Email/get in methodResponses")

        val listEl = getResp.getOrNull(1)?.jsonObject?.get("list")?.jsonArray
            ?: throw HttpException(200, "no .list in Email/get response")

        listEl.map { it.jsonObject.toEmail() }
    }

    // ── Slice C2 ───────────────────────────────────────────────────────

    data class EmailBody(
        val id: String,
        val subject: String,
        val from: String,
        val to: String,
        val cc: String,
        val receivedAt: String,
        val text: String,            // plain-text body (parts joined with \n\n)
        val hasHtml: Boolean,
    )

    /**
     * Fetch a single email with its body text. Uses fetchTextBodyValues so
     * the server returns the textBody parts inlined into bodyValues — saves
     * a second round-trip. HTML body is *not* fetched (no WebView yet);
     * `hasHtml` tells the UI a richer view is available.
     */
    fun emailBody(session: Session, emailId: String, accountId: String = session.primaryAccountId): Result<EmailBody> = wrap {
        val payload = buildJsonObject {
            put("using", buildJsonArray {
                add("urn:ietf:params:jmap:core")
                add("urn:ietf:params:jmap:mail")
            })
            put("methodCalls", buildJsonArray {
                addJsonArray {
                    add("Email/get")
                    add(buildJsonObject {
                        put("accountId", accountId)
                        put("ids", buildJsonArray { add(emailId) })
                        put("properties", buildJsonArray {
                            add("id"); add("subject"); add("from"); add("to"); add("cc")
                            add("receivedAt"); add("textBody"); add("htmlBody"); add("bodyValues")
                        })
                        put("fetchTextBodyValues", true)
                        put("bodyProperties", buildJsonArray {
                            add("partId"); add("type"); add("size")
                        })
                    })
                    add("g0")
                }
            })
        }
        val body = httpPost(session.apiUrl, payload.toString())
        val responses = json.parseToJsonElement(body).jsonObject["methodResponses"]?.jsonArray
            ?: throw HttpException(200, "no methodResponses")
        val getResp = responses.firstOrNull()?.jsonArray
            ?: throw HttpException(200, "empty methodResponses")
        val list = getResp.getOrNull(1)?.jsonObject?.get("list")?.jsonArray
            ?: throw HttpException(200, "no .list")
        val obj = list.firstOrNull()?.jsonObject
            ?: throw HttpException(200, "no email returned")

        val bodyValues = obj["bodyValues"]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val textBody = obj["textBody"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        val htmlBody = obj["htmlBody"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())

        val textJoined = textBody.mapNotNull { part ->
            val pid = part.jsonObject["partId"]?.jsonPrimitive?.content ?: return@mapNotNull null
            bodyValues[pid]?.jsonObject?.get("value")?.jsonPrimitive?.content
        }.joinToString("\n\n").ifBlank { "(no plain-text body)" }

        EmailBody(
            id         = obj["id"]!!.jsonPrimitive.content,
            subject    = obj["subject"]?.jsonPrimitive?.let { if (it.content == "null") "" else it.content } ?: "",
            from       = obj["from"]?.let { formatAddrList(it) } ?: "",
            to         = obj["to"]?.let   { formatAddrList(it) } ?: "",
            cc         = obj["cc"]?.let   { formatAddrList(it) } ?: "",
            receivedAt = obj["receivedAt"]?.jsonPrimitive?.let { if (it.content == "null") "" else it.content } ?: "",
            text       = textJoined,
            hasHtml    = htmlBody.isNotEmpty(),
        )
    }

    private fun formatAddrList(el: kotlinx.serialization.json.JsonElement): String {
        if (el is kotlinx.serialization.json.JsonNull) return ""
        return el.jsonArray.joinToString(", ") { item ->
            val o = item.jsonObject
            val name = o["name"]?.jsonPrimitive?.let { if (it.content == "null") null else it.content }
            val addr = o["email"]?.jsonPrimitive?.content ?: ""
            if (!name.isNullOrBlank()) "$name <$addr>" else addr
        }
    }

    private fun JsonObject.toEmail(): Email {
        val fromArr = this["from"]?.let { el ->
            if (el is kotlinx.serialization.json.JsonNull) null else el.jsonArray
        }
        val fromStr = fromArr?.joinToString(", ") { el ->
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.let { if (it.content == "null") null else it.content }
            val addr = o["email"]?.jsonPrimitive?.content ?: ""
            if (!name.isNullOrBlank()) "$name <$addr>" else addr
        } ?: ""
        return Email(
            id            = this["id"]!!.jsonPrimitive.content,
            subject       = this["subject"]?.jsonPrimitive?.let { if (it.content == "null") "" else it.content } ?: "",
            from          = fromStr,
            preview       = this["preview"]?.jsonPrimitive?.let { if (it.content == "null") "" else it.content } ?: "",
            receivedAt    = this["receivedAt"]?.jsonPrimitive?.let { if (it.content == "null") "" else it.content } ?: "",
            hasAttachment = this["hasAttachment"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        )
    }

    // ── HTTP plumbing ──────────────────────────────────────────────────

    private class HttpException(val code: Int, val body: String) : RuntimeException("HTTP $code")

    private fun <T> wrap(block: () -> T): Result<T> = try {
        Result.Success(block())
    } catch (h: HttpException) {
        Result.HttpError(h.code, h.body)
    } catch (t: Throwable) {
        Result.NetworkError(t.message ?: t.javaClass.simpleName)
    }

    private fun httpGet(url: String): String = openConn(url, "GET").readBody()

    private fun httpPost(url: String, payload: String): String = openConn(url, "POST").apply {
        setRequestProperty("Content-Type", "application/json")
        doOutput = true
        outputStream.use { it.write(payload.toByteArray()) }
    }.readBody()

    private fun openConn(url: String, method: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", authHeader)
            setRequestProperty("Accept", "application/json")
        }

    private fun HttpURLConnection.readBody(): String {
        val code = responseCode
        val body = (if (code in 200..299) inputStream else errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpException(code, body)
        return body
    }
}
