package com.diegonmarcos.superapp.news

import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal reader for the public feed formats data/sources.json's rss
 * entries actually use: RSS 2.0, RSS 1.0/RDF (dw-en — items are
 * siblings of `<channel>` under `<rdf:RDF>`, not nested inside it), and
 * (defensively, though none of the seeded sources are Atom) Atom.
 * Produces the same [GdeltArticle] shape the GDELT endpoints do, always
 * with `tone = null` — see that field's kdoc in NewsModels.kt for why
 * that's never 0.0.
 *
 * Uses `org.xmlpull.v1.XmlPullParser` (the interface Android's own
 * `android.util.Xml.newPullParser()` implements) rather than any new
 * dependency, same instruction as IcsParser's "no new dependency" for
 * ICS. [parse] is the only entry point that touches `android.util.Xml`
 * — the actual walk lives in [parseInternal], which is written purely
 * against the `XmlPullParser` interface so any implementation of it
 * (Android's on-device parser, or a JVM-side one like kxml2 in a
 * desktop test harness) can drive the exact same code path.
 *
 * Defensive exactly like IcsParser: one malformed `<item>`/`<entry>` —
 * missing link, unparseable date, whatever — is skipped and the rest
 * of the document survives; a totally unparseable document degrades to
 * an empty list, never throws.
 *
 * Field mapping: title -> title (HTML-stripped, entities decoded, see
 * [cleanText]); link -> url; pubDate|dc:date|updated|published ->
 * seendate, reformatted into GDELT's zero-padded
 * `yyyyMMdd'T'HHmmss'Z'` so a plain descending string sort stays
 * chronological across GDELT- and rss-sourced articles alike (see
 * NewsStore's kdoc on why that format matters); the item's
 * `<source>`/domain -> domain, preferring the `<source url="...">`
 * attribute's host (matches GDELT's own hostname-style domain field)
 * over its text content, over the article link's own host when no
 * `<source>` element is present at all. description/content is parsed
 * but never stored — nothing user-facing consumes it yet.
 *
 * Google News' `<title>` arrives as "Headline - Publisher": when no
 * `<source>` element is present (defensive fallback only — every
 * seeded Google News flavor DOES carry `<source>`) and the article's
 * link host is news.google.com, the trailing " - Publisher" is split
 * off into `domain` instead of left glued onto the title, since that
 * is the only publisher signal that particular shape of feed gives.
 *
 * YouTube Atom (`https://www.youtube.com/feeds/videos.xml?channel_id=...`)
 * is handled by this SAME code path, not a second parser — it's just
 * Atom with two extra pieces of information data/media.json's UI wants:
 * `<yt:videoId>` (a DIRECT child of `<entry>`, itemDepth+1 — captured
 * via [GdeltArticle.videoId] the exact same way title/link/pubDate
 * are) and `<media:thumbnail url="...">` (nested INSIDE
 * `<media:group>`, i.e. itemDepth+2, one level deeper than every other
 * field this parser reads — captured via a depth-independent check
 * alongside the normal itemDepth+1 walk, first occurrence wins, into
 * [GdeltArticle.thumbnail]). Both are simply absent (null) on every
 * non-YouTube feed, which every existing source already is.
 *
 * ARTICLE IMAGE ([GdeltArticle.socialimage], what the UI actually
 * renders — never [GdeltArticle.thumbnail], which stays YouTube-only):
 * real feeds carry an article's own photo in one of four different
 * shapes, tried in this priority order, largest wins when a shape
 * offers several sizes (`media:thumbnail`/`media:content` carry
 * `width`/`height`):
 *  1. `media:thumbnail url="..."` (bbc-world: one per item, but the
 *     SAME depth-independent, any-nesting-level check as the YouTube
 *     one above, so this also still doubles as the YouTube path).
 *  2. `media:content url="..."` (guardian-world: several per item, one
 *     per width) — only when it's actually an image: `medium="image"`,
 *     or `type` starting `image/`, or (guardian-world's own feed:
 *     verified by hand to carry NEITHER attribute on any of its 135
 *     `media:content` elements) neither attribute present at all.
 *     Explicitly typed non-image media (the YouTube feed's own
 *     `media:content type="application/x-shockwave-flash"`, sitting
 *     right next to its `media:thumbnail`) is excluded by name.
 *  3. `enclosure url="..." type="image/..."` (spiegel) — only when
 *     `type` starts `image/`; a podcast's `audio/mpeg` enclosure must
 *     never be treated as an image.
 *  4. The first `<img src="...">` inside `<description>` or
 *     `<content:encoded>` HTML (tagesschau — its `<description>` is
 *     plain text, only `<content:encoded>`'s CDATA carries the image).
 * dw-en, google-news/google-search/google-tech, hackernews, and
 * aljazeera carry NONE of the four — [GdeltArticle.socialimage] is
 * correctly "" (never a fabricated placeholder; the UI already themes
 * one) for those, same as before this fix, just now for the right
 * reason (verified absence, not a field the UI never read).
 *
 * SOURCE AVATAR ([GdeltArticle.sourceImage], the "tweets" view's
 * per-item publisher icon): parsed ONCE per feed, before the first
 * `<item>`/`<entry>`, from whichever of RSS's `<channel><image><url>`
 * (or RSS 1.0/RDF's document-level `<image rdf:about="..."><url>` /
 * self-closing `<image rdf:resource="...">`, both handled — dw-en uses
 * the self-closing form INSIDE `<channel>`, pointing at the same URL
 * the document-level `<image rdf:about>` also defines) or Atom's
 * `<icon>`/`<logo>` the feed actually offers, then copied onto every
 * article that feed produces. Independent of whether that feed's
 * articles carry their OWN images at all — dw-en/aljazeera both carry a
 * channel logo despite zero per-article images. NEVER fetched over the
 * network (no `/favicon.ico` probing, ever — see the FIX notes this
 * shipped alongside): a feed that offers nothing here leaves
 * [GdeltArticle.sourceImage] null, same "absent stays absent" rule as
 * the article image.
 *
 * Every extracted URL is HTML-entity-decoded (`&amp;` is common inside
 * these — see [decodeEntities]) and normalised from protocol-relative
 * (`//host/...`) to `https://host/...`; anything that still isn't
 * `http(s)://` after that is discarded rather than kept as an
 * unusable/unsafe value (see [normalizeImageUrl]).
 */
object RssParser {

    /** Entry point: obtains an Android XmlPullParser and feeds [xml]
     *  through the shared [parseInternal] walk. */
    fun parse(xml: String): List<GdeltArticle> {
        if (xml.isBlank()) return emptyList()
        return runCatching {
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            parseInternal(parser)
        }.getOrDefault(emptyList())
    }

    /**
     * The actual walk, factored out of [parse] so it can be driven by
     * ANY [XmlPullParser] implementation — [parse] always supplies
     * Android's, but nothing here calls `android.util.Xml` directly.
     * `internal` (not `private`) so a JVM-side test harness in the same
     * module can hand this a desktop XmlPullParser implementation
     * (e.g. kxml2) and exercise this exact code path outside the
     * Android runtime, instead of only being able to verify by
     * compiling against android.jar's stub implementations.
     *
     * Tracks element depth relative to each `<item>`/`<entry>` so only
     * that element's DIRECT children are read as fields — a same-named
     * descendant nested deeper (e.g. a hypothetical `<media:title>`)
     * never clobbers the real title. Text is accumulated across nested
     * sub-elements of a field without being cleared (only cleared when
     * a NEW direct-child field starts), so real nested markup inside a
     * field (rare) still concatenates sensibly; HTML embedded as
     * escaped entities or CDATA (common — see hnrss's `<description>`)
     * comes through as plain characters either way and is stripped by
     * [cleanText].
     */
    internal fun parseInternal(parser: XmlPullParser): List<GdeltArticle> {
        val out = mutableListOf<GdeltArticle>()

        var depth = 0
        var itemDepth = -1
        var itemTag = ""
        var inItem = false

        var title = StringBuilder()
        var link = ""
        var pubDate = ""
        var sourceUrlAttr = ""
        var sourceText = ""
        var videoId = ""
        var thumbnail = ""
        var textBuf = StringBuilder()

        // Per-item article-image candidates/inputs — see class kdoc's
        // "ARTICLE IMAGE" section for the priority order these feed
        // [pickItemImage]. Reset per item in resetItemState(), same as
        // every other per-item field.
        var thumbCandidates = mutableListOf<Pair<String, Long>>()
        var contentCandidates = mutableListOf<Pair<String, Long>>()
        var enclosureImg = ""
        var descBuf = ""
        var encodedBuf = ""

        // Feed-level (NOT per-item) source-avatar state — see class
        // kdoc's "SOURCE AVATAR" section. Captured once, before the
        // first item/entry starts, and never reset per-item.
        var channelImage = ""
        var channelIcon = ""
        var channelLogo = ""
        var insideChannelImage = false
        var channelImageDepth = -1
        var metaTag = ""
        var metaBuf = StringBuilder()

        fun resetItemState() {
            title = StringBuilder()
            link = ""
            pubDate = ""
            sourceUrlAttr = ""
            sourceText = ""
            videoId = ""
            thumbnail = ""
            thumbCandidates = mutableListOf()
            contentCandidates = mutableListOf()
            enclosureImg = ""
            descBuf = ""
            encodedBuf = ""
        }

        var eventType = runCatching { parser.eventType }.getOrDefault(XmlPullParser.END_DOCUMENT)
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val name = localName(runCatching { parser.name }.getOrDefault(""))
                    if (!inItem && (name == "item" || name == "entry")) {
                        inItem = true
                        itemDepth = depth
                        itemTag = name
                        resetItemState()
                    } else if (inItem && depth == itemDepth + 1) {
                        textBuf = StringBuilder()
                        when (name) {
                            "link" -> {
                                // Atom: <link href="..." rel="alternate"/>, no text child.
                                val href = runCatching { parser.getAttributeValue(null, "href") }.getOrNull()
                                if (!href.isNullOrBlank()) {
                                    val rel = runCatching { parser.getAttributeValue(null, "rel") }.getOrNull()
                                    if (link.isEmpty() || rel == null || rel == "alternate") link = href
                                }
                            }
                            "source" -> {
                                sourceUrlAttr = runCatching { parser.getAttributeValue(null, "url") }.getOrNull().orEmpty()
                            }
                        }
                    } else if (!inItem) {
                        // Feed-level source-avatar capture — see class
                        // kdoc's "SOURCE AVATAR" section. Only reachable
                        // before the first item/entry starts (or between/
                        // after items, harmlessly, since every guard below
                        // is itself a "first occurrence wins" check).
                        when {
                            name == "image" && !insideChannelImage && channelImage.isEmpty() -> {
                                insideChannelImage = true
                                channelImageDepth = depth
                                // RSS 1.0/RDF: <image rdf:resource="..."/>
                                // (self-closing, inside <channel>) or
                                // <image rdf:about="..."> (document-level,
                                // nested <url> handled by the branch below).
                                val res = attr(parser, "resource") ?: attr(parser, "about")
                                if (!res.isNullOrBlank()) channelImage = res
                            }
                            insideChannelImage && depth == channelImageDepth + 1 && name == "url" -> {
                                metaTag = "chanimg"
                                metaBuf = StringBuilder()
                            }
                            !insideChannelImage && name == "icon" && channelIcon.isEmpty() -> {
                                metaTag = "icon"
                                metaBuf = StringBuilder()
                            }
                            !insideChannelImage && name == "logo" && channelLogo.isEmpty() -> {
                                metaTag = "logo"
                                metaBuf = StringBuilder()
                            }
                        }
                    }
                    // media:thumbnail lives INSIDE media:group on a
                    // YouTube feed, i.e. one level deeper than every
                    // other captured field (see class kdoc), but sits
                    // directly under <item> on e.g. bbc-world — checked
                    // independently of the itemDepth+1 branch above so
                    // it's found regardless of nesting depth within the
                    // item. `thumbnail` (YouTube's own field) keeps its
                    // original first-occurrence-wins/unnormalised
                    // behaviour untouched; the same url ALSO feeds
                    // [thumbCandidates] (normalised, every occurrence,
                    // largest wins) for the article-image priority chain.
                    if (inItem && name == "thumbnail") {
                        val url = attr(parser, "url")
                        if (thumbnail.isEmpty()) thumbnail = url.orEmpty()
                        normalizeImageUrl(url)?.let {
                            thumbCandidates.add(it to mediaScore(attr(parser, "width"), attr(parser, "height")))
                        }
                    }
                    // media:content — only ever an image candidate when
                    // [isImageMediaContent] agrees (see class kdoc);
                    // disambiguated from Atom's own unrelated <content>
                    // element by requiring a `url` attribute, which only
                    // media:content ever carries.
                    if (inItem && name == "content") {
                        val url = attr(parser, "url")
                        if (!url.isNullOrBlank() && isImageMediaContent(attr(parser, "medium"), attr(parser, "type"))) {
                            normalizeImageUrl(url)?.let {
                                contentCandidates.add(it to mediaScore(attr(parser, "width"), attr(parser, "height")))
                            }
                        }
                    }
                    // enclosure — only when explicitly typed as an image
                    // (a podcast's audio/mpeg enclosure must never be
                    // mistaken for a photo). First occurrence wins.
                    if (inItem && name == "enclosure" && enclosureImg.isEmpty()) {
                        val url = attr(parser, "url")
                        val type = attr(parser, "type")
                        if (!url.isNullOrBlank() && type?.startsWith("image/", ignoreCase = true) == true) {
                            normalizeImageUrl(url)?.let { enclosureImg = it }
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val text = runCatching { parser.text }.getOrDefault("")
                    if (inItem) textBuf.append(text)
                    if (metaTag.isNotEmpty()) metaBuf.append(text)
                }
                XmlPullParser.END_TAG -> {
                    val name = localName(runCatching { parser.name }.getOrDefault(""))
                    if (inItem && depth == itemDepth + 1) {
                        val text = textBuf.toString()
                        when (name) {
                            "title" -> title.append(text)
                            "link" -> if (link.isEmpty()) link = text.trim()
                            "pubDate", "published", "updated", "date" -> if (pubDate.isEmpty()) pubDate = text.trim()
                            "source" -> if (sourceText.isEmpty()) sourceText = text.trim()
                            "videoId" -> if (videoId.isEmpty()) videoId = text.trim()
                            // Raw (unstripped) HTML kept verbatim — only
                            // ever consumed by [firstImgSrc] as a last-
                            // resort article-image source (see class
                            // kdoc); never shown to the user as text.
                            "description" -> if (descBuf.isEmpty()) descBuf = text
                            "encoded" -> if (encodedBuf.isEmpty()) encodedBuf = text
                        }
                    } else if (!inItem) {
                        // Closes out the feed-level source-avatar capture
                        // started in the START_TAG branch above.
                        when {
                            metaTag == "chanimg" && name == "url" -> {
                                channelImage = metaBuf.toString().trim()
                                metaTag = ""
                            }
                            metaTag == "icon" && name == "icon" -> {
                                channelIcon = metaBuf.toString().trim()
                                metaTag = ""
                            }
                            metaTag == "logo" && name == "logo" -> {
                                channelLogo = metaBuf.toString().trim()
                                metaTag = ""
                            }
                        }
                        if (insideChannelImage && name == "image" && depth == channelImageDepth) {
                            insideChannelImage = false
                            channelImageDepth = -1
                        }
                    }
                    if (inItem && name == itemTag && depth == itemDepth) {
                        val itemImage = pickItemImage(thumbCandidates, contentCandidates, enclosureImg, descBuf, encodedBuf)
                        val chanRaw: String? = when {
                            channelImage.isNotBlank() -> channelImage
                            channelIcon.isNotBlank() -> channelIcon
                            channelLogo.isNotBlank() -> channelLogo
                            else -> null
                        }
                        val sourceImage = normalizeImageUrl(chanRaw)
                        runCatching {
                            finishItem(title.toString(), link, pubDate, sourceUrlAttr, sourceText, videoId, thumbnail, itemImage, sourceImage)
                        }
                            .getOrNull()
                            ?.let { out.add(it) }
                        inItem = false
                        itemDepth = -1
                    }
                    textBuf = StringBuilder()
                    depth--
                }
            }
            eventType = runCatching { parser.next() }.getOrDefault(XmlPullParser.END_DOCUMENT)
        }
        return out
    }

    /** Builds one [GdeltArticle] from one item's raw fields, or returns
     *  null (caller skips it) for a link-less or title-less item —
     *  same "drop the one bad record, keep the rest" contract as
     *  IcsParser's malformed VEVENT handling. */
    private fun finishItem(
        rawTitle: String,
        rawLink: String,
        rawPubDate: String,
        sourceUrlAttr: String,
        sourceText: String,
        rawVideoId: String = "",
        rawThumbnail: String = "",
        itemImage: String = "",
        sourceImage: String? = null,
    ): GdeltArticle? {
        val link = rawLink.trim()
        if (link.isEmpty()) return null
        var title = cleanText(rawTitle)
        if (title.isEmpty()) return null

        val linkHost = hostOf(link)
        var domain = hostOf(sourceUrlAttr).orEmpty().ifEmpty { cleanText(sourceText) }

        // Google News-only fallback: only when no <source> element gave
        // us a domain AND the link is actually a news.google.com
        // redirect — see class kdoc.
        if (domain.isEmpty() && linkHost == "news.google.com") {
            val idx = title.lastIndexOf(" - ")
            if (idx > 0 && idx < title.length - 3) {
                val publisher = title.substring(idx + 3).trim()
                if (publisher.isNotEmpty()) {
                    title = title.substring(0, idx).trim()
                    domain = publisher
                }
            }
        }
        if (domain.isEmpty()) domain = linkHost.orEmpty()
        if (title.isEmpty()) return null

        return GdeltArticle(
            url = link,
            title = title,
            seendate = parseDate(rawPubDate),
            socialimage = itemImage,
            domain = domain,
            language = "",
            sourcecountry = "",
            tone = null,
            videoId = rawVideoId.trim().takeIf { it.isNotEmpty() },
            thumbnail = rawThumbnail.trim().takeIf { it.isNotEmpty() },
            sourceImage = sourceImage,
        )
    }

    // ---- small helpers ----------------------------------------------------

    private fun localName(name: String): String {
        val idx = name.indexOf(':')
        return if (idx >= 0) name.substring(idx + 1) else name
    }

    private fun hostOf(url: String): String? {
        if (url.isBlank()) return null
        return runCatching { URI(url.trim()).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
    }

    /** Namespace-prefix-tolerant attribute lookup: with
     *  `FEATURE_PROCESS_NAMESPACES` off (see [parse]) an attribute's raw
     *  name still carries its prefix (e.g. `rdf:resource`), so a plain
     *  `getAttributeValue(null, "resource")` would miss it — this scans
     *  every attribute and compares [localName]s instead. Never throws;
     *  null for a missing attribute or an unreadable parser state. */
    private fun attr(parser: XmlPullParser, name: String): String? {
        val count = runCatching { parser.attributeCount }.getOrDefault(0)
        for (i in 0 until count) {
            val attrName = runCatching { parser.getAttributeName(i) }.getOrNull() ?: continue
            if (localName(attrName) == name) return runCatching { parser.getAttributeValue(i) }.getOrNull()
        }
        return null
    }

    /** `width`/`height` -> a comparable size score for picking the
     *  LARGEST of several `media:thumbnail`/`media:content` candidates
     *  (see class kdoc). Both present -> area; only one present -> that
     *  one (still orders correctly against same-shape candidates that
     *  only ever supply one dimension, e.g. guardian-world's
     *  width-only `media:content`); neither present -> 0, so a
     *  dimensionless candidate never outranks a sized one but is still
     *  usable if it's the only candidate at all. */
    private fun mediaScore(w: String?, h: String?): Long {
        val wi = w?.toLongOrNull()
        val hi = h?.toLongOrNull()
        return when {
            wi != null && hi != null -> wi * hi
            wi != null -> wi
            hi != null -> hi
            else -> 0L
        }
    }

    /** Whether a `media:content` element is actually a photo (see class
     *  kdoc's "ARTICLE IMAGE" section point 2) rather than some other
     *  media type it's also legally used for. An explicit `medium`
     *  attribute is authoritative when present; failing that an explicit
     *  `type` must start `image/`; failing THAT (guardian-world's own
     *  135 `media:content` elements, verified by hand, carry neither
     *  attribute at all) default-accept — deliberately more permissive
     *  than the strict "must be explicitly typed as an image" reading,
     *  because that stricter reading would silently zero out a feed this
     *  fix is specifically meant to un-break. Explicitly typed non-image
     *  media (e.g. a YouTube feed's own `media:content
     *  type="application/x-shockwave-flash"`, sitting right next to its
     *  `media:thumbnail`) is still correctly excluded either way. */
    private fun isImageMediaContent(medium: String?, type: String?): Boolean {
        if (!medium.isNullOrBlank()) return medium.equals("image", ignoreCase = true)
        if (!type.isNullOrBlank()) return type.startsWith("image/", ignoreCase = true)
        return true
    }

    /** Decodes entities and normalises a protocol-relative
     *  (`//host/path`) url to `https://host/path` (see class kdoc). Null
     *  (never a raw/unusable string) for a blank input or anything that
     *  still isn't `http://`/`https://` afterwards — an absent/malformed
     *  image url is dropped, never fabricated or passed through
     *  unusable. */
    private fun normalizeImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val decoded = decodeEntities(raw.trim())
        val withScheme = if (decoded.startsWith("//")) "https:$decoded" else decoded
        return withScheme.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    private val IMG_SRC_RE = Regex("(?i)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']")

    /** First `<img src="...">` found in raw (unstripped) HTML — the
     *  last-resort article-image source (class kdoc point 4), e.g.
     *  tagesschau's `<content:encoded>` CDATA. Standard XML entity
     *  decoding (CDATA or escaped) already happened by the time this
     *  text reached [descBuf]/[encodedBuf], so this only has to find the
     *  tag, not decode it — but the extracted `src` VALUE may still
     *  carry `&amp;` etc. if the source HTML itself double-escaped it,
     *  so callers still run the result through [normalizeImageUrl]. */
    private fun firstImgSrc(html: String): String? {
        if (html.isBlank()) return null
        return IMG_SRC_RE.find(html)?.groupValues?.get(1)
    }

    /** The article-image priority chain from the class kdoc's "ARTICLE
     *  IMAGE" section: `media:thumbnail` (largest) -> `media:content`
     *  (largest, image-typed only) -> `enclosure` (image-typed only) ->
     *  first `<img>` in the description/content HTML -> "" (never a
     *  fabricated placeholder — see [GdeltArticle.socialimage]'s
     *  contract, unchanged by this fix). [thumbCandidates]/
     *  [contentCandidates] entries are already normalised at collection
     *  time; the HTML-sourced fallback is normalised here since
     *  [firstImgSrc] only extracts, never cleans, the raw attribute
     *  value. */
    private fun pickItemImage(
        thumbCandidates: List<Pair<String, Long>>,
        contentCandidates: List<Pair<String, Long>>,
        enclosureImg: String,
        descBuf: String,
        encodedBuf: String,
    ): String {
        thumbCandidates.maxByOrNull { it.second }?.let { return it.first }
        contentCandidates.maxByOrNull { it.second }?.let { return it.first }
        if (enclosureImg.isNotEmpty()) return enclosureImg
        normalizeImageUrl(firstImgSrc(descBuf))?.let { return it }
        normalizeImageUrl(firstImgSrc(encodedBuf))?.let { return it }
        return ""
    }

    private val TAG_RE = Regex("<[^>]*>")
    private val WS_RE = Regex("\\s+")

    /** Strips HTML tags and decodes HTML entities — both routinely
     *  embedded in these feeds' titles (Google News wraps its
     *  `<description>` in escaped HTML; hnrss wraps its `<description>`
     *  in raw HTML inside CDATA; titles are usually clean but this is
     *  applied defensively to every title regardless). Collapses
     *  whitespace and trims. */
    private fun cleanText(raw: String): String {
        if (raw.isBlank()) return ""
        val noTags = TAG_RE.replace(raw, " ")
        val decoded = decodeEntities(noTags)
        return WS_RE.replace(decoded, " ").trim()
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 10)) {
                    val replacement = namedOrNumericEntity(s.substring(i + 1, semi))
                    if (replacement != null) {
                        sb.append(replacement)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun namedOrNumericEntity(entity: String): String? = when {
        entity == "amp" -> "&"
        entity == "lt" -> "<"
        entity == "gt" -> ">"
        entity == "quot" -> "\""
        entity == "apos" -> "'"
        entity == "nbsp" -> " "
        entity.startsWith("#x", ignoreCase = true) ->
            entity.substring(2).toIntOrNull(16)?.let { runCatching { it.toChar().toString() }.getOrNull() }
        entity.startsWith("#") ->
            entity.substring(1).toIntOrNull()?.let { runCatching { it.toChar().toString() }.getOrNull() }
        else -> null
    }

    private val GDELT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** Reformats an RFC-822 (`Mon, 10 Aug 2026 13:19:39 GMT`, used by
     *  RSS 2.0's pubDate) or ISO-8601 (`2026-08-10T13:07:00Z`, used by
     *  RDF's dc:date and Atom's updated/published) date into GDELT's
     *  own `yyyyMMdd'T'HHmmss'Z'`, always normalized to UTC, so
     *  [NewsStore]'s "sort by seendate as a plain string" trick stays
     *  correct across sources. Returns "" (never throws) for anything
     *  that doesn't parse as one of the above — callers still keep the
     *  article, just without a sortable date; NewsBridge's descending
     *  string sort puts empty-seendate articles last. */
    private fun parseDate(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""

        runCatching {
            java.time.ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME)
        }.getOrNull()?.let { return it.withZoneSameInstant(ZoneOffset.UTC).format(GDELT_FMT) }

        runCatching { OffsetDateTime.parse(t) }.getOrNull()
            ?.let { return it.withOffsetSameInstant(ZoneOffset.UTC).format(GDELT_FMT) }

        runCatching { LocalDateTime.parse(t) }.getOrNull()
            ?.let { return it.atZone(ZoneOffset.UTC).format(GDELT_FMT) }

        runCatching { LocalDate.parse(t) }.getOrNull()
            ?.let { return it.atStartOfDay(ZoneOffset.UTC).format(GDELT_FMT) }

        return ""
    }
}
