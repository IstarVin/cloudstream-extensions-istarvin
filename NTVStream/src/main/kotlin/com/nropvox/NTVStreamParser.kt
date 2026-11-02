package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure parsing for ntv.st: the two JSON lists, page URLs, match timing and the embed pages of the supported hosts. */
object NTVStreamParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** A match is shown as live from 15 minutes before its start until this long after it. */
    private const val LIVE_BEFORE_MS = 15 * 60_000L
    private const val LIVE_AFTER_MS = 4 * 3_600_000L

    /** Sources on these hosts can be resolved without a browser; see `ntv.st-api.md` §6.3. */
    private const val CDNLIVE_HOST = "cdnlivetv.tv"
    private const val HESGOAL_HOST = "hesgoal.team"

    fun matches(json: String): NtvMatchList? =
        runCatching { mapper.readValue<NtvMatchList>(json) }.getOrNull()?.takeIf { it.success }

    fun channels(json: String): NtvChannelList? =
        runCatching { mapper.readValue<NtvChannelList>(json) }.getOrNull()?.takeIf { it.success }

    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    /** Whether [resolveEmbed] has a recipe for this embed page. */
    fun isSupported(url: String): Boolean = when (hostOf(url)) {
        CDNLIVE_HOST, HESGOAL_HOST -> true
        else -> false
    }

    fun playable(match: NtvMatch): List<NtvSource> =
        match.sources.filter { !it.url.isNullOrBlank() && isSupported(it.url) }

    /** Channels on a supported server; dlhd (Phoenix) and scorpion ones have no resolvable embed. */
    fun isSupported(channel: NtvChannel): Boolean =
        !channel.id.isNullOrBlank() && !channel.name.isNullOrBlank() && !channel.url.isNullOrBlank() && isSupported(channel.url)

    /** Epoch ms of a start `date` that is a real time, or null for a 24/7 or unknown entry. */
    private fun start(match: NtvMatch): Long? = match.date?.takeIf { it > 0 }

    fun isLive(match: NtvMatch, now: Long): Boolean {
        val start = start(match) ?: return false
        return now >= start - LIVE_BEFORE_MS && now <= start + LIVE_AFTER_MS
    }

    /** Matches that have not started yet, nearest first. */
    fun upcoming(matches: List<NtvMatch>, now: Long): List<NtvMatch> =
        matches.filter { (start(it) ?: return@filter false) - LIVE_BEFORE_MS > now }.sortedBy { start(it) }

    private val emoji = Regex("""[\p{So}\p{Cs}️‍]""")
    private val edges = Regex("""^[\s\-–—|:•·,*_~]+|[\s\-–—|:•·,*_~]+$""")

    /** The site strips pictographs and double-encoded entities from titles; do the same. */
    fun cleanTitle(title: String): String {
        val decoded = decodeEntities(decodeEntities(title))
        return decoded.replace(emoji, "").replace(Regex("""\(\s*\)|\[\s*\]"""), "").replace(Regex("""\s+"""), " ")
            .replace(edges, "").trim().ifEmpty { title.trim() }
    }

    private val entity = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val named = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ")

    private fun decodeEntities(text: String) = entity.replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> named[e.lowercase()]
        } ?: m.value
    }

    fun matchPlot(match: NtvMatch, now: Long): String? {
        val start = start(match)
        val when_ = when {
            start == null -> null
            isLive(match, now) -> "Live now"
            else -> SimpleDateFormat("EEE d MMM HH:mm 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(start))
        }
        return listOfNotNull(
            match.tournament?.trim()?.ifEmpty { null },
            match.category?.trim()?.ifEmpty { null },
            when_,
        ).joinToString(" · ").ifBlank { null }
    }

    // ---- page URLs -----------------------------------------------------------------------------------------

    fun matchUrl(mainUrl: String, server: String, id: String) = "$mainUrl/watch/$server/${enc(id)}"

    /** A channel page URL that also carries what `load` needs, so it needs no catalog lookup. */
    fun channelUrl(mainUrl: String, channel: NtvChannel): String =
        "$mainUrl/channel/${channel.server}/${enc(channel.id!!)}?u=${enc(channel.url!!)}&n=${enc(channel.name!!)}"

    /** `/watch/<server>/<id>` → (server, id). */
    fun matchRef(url: String): Pair<String, String>? =
        Regex("""/watch/([a-z]+)/([^/?#]+)""").find(url)?.let { it.groupValues[1] to dec(it.groupValues[2]) }

    /** The embed URL and display name a [channelUrl] was built from. */
    fun channelRef(url: String): Pair<String, String>? {
        if (!url.contains("/channel/")) return null
        val query = url.substringAfter('?', "")
        val params = query.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to dec(it.substringAfter('=')) }
        val embed = params["u"]?.takeIf { it.isNotBlank() } ?: return null
        return embed to (params["n"] ?: "")
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    private fun dec(text: String) = URLDecoder.decode(text, "UTF-8")

    fun linksJson(links: List<NtvLink>): String = mapper.writeValueAsString(links)

    fun links(json: String): List<NtvLink> = runCatching { mapper.readValue<List<NtvLink>>(json) }.getOrDefault(emptyList())

    // ---- embed pages ---------------------------------------------------------------------------------------

    /** Quality from the channel label ("ESPN HD US", "Sky Sports FHD UK"); 480 when it says nothing. */
    fun quality(label: String): Int {
        val upper = label.uppercase()
        return when {
            Regex("""\b(4K|UHD)\b""").containsMatchIn(upper) -> 2160
            Regex("""\bFHD\b|\b1080""").containsMatchIn(upper) -> 1080
            Regex("""\bHD\b|\b720""").containsMatchIn(upper) -> 720
            else -> 480
        }
    }

    /** The page URL (`ntvtvplayer.html?id=…`) → the wideiptv.top player page for that channel. */
    fun wideiptvPlayer(embedUrl: String): String? {
        val id = Regex("""[?&]id=([^&#]+)""").find(embedUrl)?.groupValues?.get(1)?.let(::dec) ?: return null
        val clean = id.trim().replace(Regex("""[/\\.?&=]"""), "")
        return clean.takeIf { it.isNotEmpty() }?.let { "https://wideiptv.top/player/${enc(it)}" }
    }

    /** The channel id of a `ntvtvplayer.html?id=…` embed, as wideiptv.top names it. */
    fun wideiptvChannel(embedUrl: String): String? =
        wideiptvPlayer(embedUrl)?.substringAfterLast('/')?.let(::dec)

    /** `token` of `POST wideiptv.top/api/refresh_token.php`. */
    fun wideiptvToken(json: String): String? =
        Regex(""""token"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    /** The playlist line of the highest-BANDWIDTH variant in a master playlist, or null for a media playlist. */
    fun bestVariant(master: String): String? {
        val lines = master.lines().map { it.trim() }
        var best: String? = null
        var bestBandwidth = -1L
        lines.forEachIndexed { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val bandwidth = Regex("""[:,]BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            if (bandwidth > bestBandwidth) { bestBandwidth = bandwidth; best = uri }
        }
        return best
    }

    /** [uri] with its `token` query value replaced (or added). */
    fun withToken(uri: String, token: String): String = uri.substringBefore('?') + "?token=" + token

    /** Makes every relative URI of a media playlist absolute against [base], so it can be served from another host. */
    fun absolutize(playlist: String, base: String): String {
        val baseUri = URI(base)
        return playlist.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> Regex("""URI="([^"]+)"""").replace(line) { m ->
                    "URI=\"" + baseUri.resolve(m.groupValues[1]) + "\""
                }
                else -> baseUri.resolve(trimmed).toString()
            }
        }
    }

    /**
     * The cdnlivetv.tv player page assembles its playlist URL from base64url chunks:
     * `var A='aHR0cHM'; … var X=f(A)+f(B)+…;`. Variable names change on every request.
     */
    fun cdnlivePlaylist(html: String): String? {
        val chunks = Regex("""var (\w+)='([A-Za-z0-9_\-]+)';""").findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
        val order = Regex("""var \w+=((?:\w+\(\w+\)\+?)+);""").findAll(html)
            .map { m -> Regex("""\((\w+)\)""").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList() }
            .firstOrNull { names -> names.size > 1 && names.all { it in chunks } } ?: return null
        val parts = order.map { unb64(chunks.getValue(it)) ?: return null }
        return parts.joinToString("").takeIf { it.startsWith("http") && ".m3u8" in it }
    }

    private fun unb64(chunk: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(chunk.padEnd((chunk.length + 3) / 4 * 4, '=')), Charsets.UTF_8)
    }.getOrNull()
}
