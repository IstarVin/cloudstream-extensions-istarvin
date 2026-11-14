package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure parsing for strmfree.pk: the public JSON API, page URLs, the embed page's signed playlist and match timing. */
object StrmFreeParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** A stream that is not up yet still counts as upcoming for this long after its start time (it may be late). */
    private const val UPCOMING_GRACE_S = 3 * 3_600L

    // ---- JSON API ------------------------------------------------------------------------------------------

    /** Every listed stream that has what a card needs. */
    fun streams(json: String): List<SfStream> =
        runCatching { mapper.readValue<SfStreamList>(json).streams }.getOrDefault(emptyList())
            .filter { !it.key.isNullOrBlank() && !it.name.isNullOrBlank() && !it.category.isNullOrBlank() }

    fun stream(json: String): SfStream? =
        runCatching { mapper.readValue<SfStream>(json) }.getOrNull()
            ?.takeIf { !it.key.isNullOrBlank() && !it.name.isNullOrBlank() && !it.category.isNullOrBlank() }

    fun sources(json: String): List<SfSource> =
        runCatching { mapper.readValue<SfSourceList>(json).sources }.getOrDefault(emptyList())
            .filter { !it.url.isNullOrBlank() }

    fun streamKey(json: String): SfStreamKey? = runCatching { mapper.readValue<SfStreamKey>(json) }.getOrNull()

    fun isLive(stream: SfStream) = stream.sources.any { it.live }

    /** Live streams first (most watched on top), then the rest by start time. */
    fun ordered(streams: List<SfStream>): List<SfStream> =
        streams.sortedWith(compareByDescending<SfStream> { isLive(it) }.thenBy { if (isLive(it)) -it.viewers else 0 }
            .thenBy { it.timestamp ?: Long.MAX_VALUE })

    fun live(streams: List<SfStream>): List<SfStream> =
        streams.filter(::isLive).sortedByDescending { it.viewers }

    /** Not up yet and starting soon (or only just late), nearest first. [nowS] is unix seconds. */
    fun upcoming(streams: List<SfStream>, nowS: Long): List<SfStream> =
        streams.filter { !isLive(it) && (it.timestamp ?: return@filter false) > nowS - UPCOMING_GRACE_S }
            .sortedBy { it.timestamp }

    fun inCategory(streams: List<SfStream>, category: String): List<SfStream> =
        ordered(streams.filter { it.category.equals(category, ignoreCase = true) })

    fun matches(stream: SfStream, query: String): Boolean =
        listOf(stream.name, stream.league, stream.category, stream.key).any { it?.contains(query, ignoreCase = true) == true }

    fun categoryTitle(category: String): String = category.replaceFirstChar { it.uppercase() }

    // ---- page URLs -----------------------------------------------------------------------------------------

    /** The page `load` is given; carries both the category and the key, so a lookup needs nothing else. */
    fun playerUrl(mainUrl: String, category: String, key: String) = "$mainUrl/player/${enc(category)}/${enc(key)}"

    /** `/player/<category>/<key>` → (category, key). */
    fun playerRef(url: String): Pair<String, String>? =
        Regex("""/player/([^/?#]+)/([^/?#]+)""").find(url)?.let { dec(it.groupValues[1]) to dec(it.groupValues[2]) }

    fun embedUrl(mainUrl: String, category: String, variant: String) = "$mainUrl/embed/${enc(category)}/$variant"

    /** The thumbnail as an absolute URL (the list endpoint `/streams` returns it site-relative). */
    fun thumbnail(mainUrl: String, stream: SfStream): String? =
        stream.thumbnail?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) mainUrl + it else it }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    private fun dec(text: String) = URLDecoder.decode(text, "UTF-8")

    // ---- sources -------------------------------------------------------------------------------------------

    /** `https://host/embed/soccer/ghana-vs-england1080p2` → `ghana-vs-england1080p2`. */
    fun variantOf(sourceUrl: String): String = sourceUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')

    /** Source labels are SD, HD, FHD and 4K. */
    fun heightOfLabel(label: String?): Int = when (label?.uppercase()) {
        "4K" -> 2160
        "FHD" -> 1080
        "HD" -> 720
        else -> 480
    }

    /** `1080p` → 1080. */
    fun heightOfQuality(quality: String): Int = quality.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    fun sourceLabel(site: String, source: SfSource, index: Int): String {
        val parts = listOfNotNull(source.quality?.ifBlank { null }, source.broadcast?.trim()?.ifEmpty { null })
        return (listOf(site) + parts.ifEmpty { listOf("Source ${index + 1}") }).joinToString(" ")
    }

    // ---- embed page and playlist -----------------------------------------------------------------------------

    /**
     * The constants of an embed page:
     * `const QUALITY = '1080p'; const SOURCE_SUFFIX = ''; const VARIANT = 'skyf1' + QUALITY + SOURCE_SUFFIX;
     * const IS_LIVE = true; const _0x = {"1080p": {"_e": …, "_n": "…", "_t": "…"}, …};`
     */
    fun embed(html: String): SfEmbed? {
        val key = Regex("""const VARIANT = '([^']+)'""").find(html)?.groupValues?.get(1) ?: return null
        val quality = Regex("""const QUALITY = '([^']+)'""").find(html)?.groupValues?.get(1) ?: return null
        val suffix = Regex("""const SOURCE_SUFFIX = '([^']*)'""").find(html)?.groupValues?.get(1).orEmpty()
        val live = Regex("""const IS_LIVE = (true|false)""").find(html)?.groupValues?.get(1) == "true"
        val tokens = Regex("""const _0x = (\{.*\});""").find(html)?.groupValues?.get(1)
            ?.let { runCatching { mapper.readValue<Map<String, SfToken>>(it) }.getOrNull() }
        return SfEmbed(key, quality, suffix, live, tokens?.get(quality)?.takeIf { it.t.isNotEmpty() })
    }

    /**
     * The CDN path prefixes to try, the one `/get-stream-key` names first. The embed page maps `externalcdn` to
     * `live-externalcdn`, any other named server to `live-cdn` and `origin` to `live`.
     */
    fun playlistPrefixes(serverName: String?): List<String> {
        val all = listOf("live-externalcdn", "live-cdn", "live")
        val first = when (serverName?.lowercase()) {
            "externalcdn" -> "live-externalcdn"
            null, "", "origin" -> "live"
            else -> "live-cdn"
        }
        return listOf(first) + all.filter { it != first }
    }

    fun playlistUrl(base: String, prefix: String, embed: SfEmbed): String? {
        val token = embed.token ?: return null
        return "$base/$prefix/${embed.variant}/index.m3u8?_t=${token.t}&_e=${token.e}&_n=${token.n}"
    }

    /** A real playlist, not the nginx 403 page. */
    fun isPlaylist(body: String) = body.trimStart().startsWith("#EXTM3U")

    // ---- plot ----------------------------------------------------------------------------------------------

    fun plot(stream: SfStream, nowS: Long): String? {
        val start = stream.timestamp?.takeIf { it > 0 }
        val whenText = when {
            isLive(stream) -> "Live now"
            start == null -> null
            else -> SimpleDateFormat("EEE d MMM HH:mm 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(start * 1000)).let { if (start > nowS) "Starts $it" else "Scheduled $it" }
        }
        val broadcasts = stream.sources.mapNotNull { it.broadcast?.trim()?.ifEmpty { null } }.distinct()
        return listOfNotNull(
            stream.league?.trim()?.ifEmpty { null },
            whenText,
            broadcasts.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "On "),
        ).joinToString(" · ").ifBlank { null }
    }
}
