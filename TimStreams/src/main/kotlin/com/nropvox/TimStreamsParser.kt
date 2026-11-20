package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Pure logic for tim-streams.com: JSON lists, page URLs, posters, stream selection and the HLS playlists. */
object TimStreamsParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    const val KIND_EVENT = "watch"
    const val KIND_CHANNEL = "channel"
    const val KIND_REPLAY = "replay"

    /** Row title of events whose genre is unnamed or a placeholder ("TBA" in the site's genre table). */
    const val OTHER_EVENTS = "Other"

    /** Master and variant playlists want an `Origin` of the embed host and a browser UA; segments need nothing. */
    const val EMBED_ORIGIN = "https://ehgembxe.cfd"
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    // ---- JSON -------------------------------------------------------------------------------------------------

    fun events(json: String): TsEvents? = runCatching { mapper.readValue<TsEvents>(json) }.getOrNull()

    fun channels(json: String): TsChannels? = runCatching { mapper.readValue<TsChannels>(json) }.getOrNull()

    fun replays(json: String): TsReplays? = runCatching { mapper.readValue<TsReplays>(json) }.getOrNull()

    fun watch(json: String): TsWatch? = runCatching { mapper.readValue<TsWatch>(json) }.getOrNull()?.takeIf { it.item != null }

    // ---- items ------------------------------------------------------------------------------------------------

    /** Non-VIP streams with a URL. An item without one is not listed (VIP-only channels). */
    fun playable(item: TsItem): List<TsStream> =
        if (item.vip) emptyList() else item.streams.filter { !it.vip && !it.url.isNullOrBlank() }

    fun isListed(item: TsItem): Boolean = !item.url.isNullOrBlank() && !item.name.isNullOrBlank() && playable(item).isNotEmpty()

    /** Events: `/img/<league>-<id>.png` is site-relative. Channels and replays already carry absolute URLs. */
    fun posterUrl(logo: String?, mainUrl: String): String? {
        val value = logo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            value.startsWith("http://") || value.startsWith("https://") -> value
            else -> mainUrl.trimEnd('/') + "/" + value
        }
    }

    fun pageUrl(mainUrl: String, kind: String, slug: String): String = "${mainUrl.trimEnd('/')}/$kind/$slug"

    /** `https://tim-streams.com/watch/<slug>` -> ("watch", slug); also channel and replay pages. */
    fun pageRef(url: String): Pair<String, String>? {
        val m = Regex("""^https?://[^/]+/(watch|channel|replay)/([^/?#]+)""").find(url.trim()) ?: return null
        return m.groupValues[1] to URLDecoder.decode(m.groupValues[2], "UTF-8")
    }

    /** "2026-11-19T19:00" -> "19:00"; null for a time that doesn't look like one. */
    fun clock(time: String?): String? =
        Regex("""T(\d{2}:\d{2})""").find(time.orEmpty())?.groupValues?.get(1)

    /** "2026-11-19T19:00" -> "2026-11-19 19:00". */
    fun dateTime(time: String?): String? =
        Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})""").find(time.orEmpty())?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }

    /** Card title of an event: the clock in front so a row reads like a schedule. */
    fun eventTitle(item: TsItem): String {
        val name = item.name.orEmpty().trim()
        val at = clock(item.time) ?: return name
        return "$at  $name"
    }

    fun genreName(genres: List<TsGenre>, id: String?): String =
        genres.firstOrNull { it.id == id }?.name?.trim()?.takeIf { it.isNotEmpty() && !it.equals("TBA", ignoreCase = true) } ?: OTHER_EVENTS

    fun subGenreName(genres: List<TsGenre>, genre: String?, sub: String?): String? =
        genres.firstOrNull { it.id == genre }?.subCategories?.firstOrNull { it.id == sub }?.name?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Event rows in the order of the site's genre table. Genres sharing a name ("Rugby" twice, two "TBA") merge, and
     * a genre without events gets no row.
     */
    fun eventRows(events: TsEvents): List<Pair<String, List<TsItem>>> {
        val listed = events.events.filter(::isListed)
        val order = events.genres.map { genreName(events.genres, it.id) }.distinct()
        val byName = listed.groupBy { genreName(events.genres, it.genre) }
        val ordered = order.filter { it in byName } + byName.keys.filter { it !in order }
        return ordered.map { it to byName.getValue(it) }
    }

    /** Channel rows in the order of the channel genre table. */
    fun channelRows(channels: TsChannels): List<Pair<String, List<TsItem>>> {
        val listed = channels.channels.filter(::isListed)
        val byId = listed.groupBy { it.genre }
        val known = channels.genres.mapNotNull { g ->
            val items = byId[g.id] ?: return@mapNotNull null
            "Live TV - ${g.name.orEmpty().trim().ifEmpty { "Other" }}" to items
        }
        val unknown = byId.filterKeys { id -> channels.genres.none { it.id == id } }.values.flatten()
        return known + if (unknown.isEmpty()) emptyList() else listOf("Live TV - Other" to unknown)
    }

    /** Client-side search: every word of the query appears in the title or genre. */
    fun matches(item: TsItem, genre: String?, query: String): Boolean {
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val haystack = (item.name.orEmpty() + " " + genre.orEmpty()).lowercase()
        return words.all { it in haystack }
    }

    // ---- data -------------------------------------------------------------------------------------------------

    fun linksJson(links: List<TsLink>): String = mapper.writeValueAsString(links)

    fun links(json: String): List<TsLink> =
        runCatching { mapper.readValue<List<TsLink>>(json) }.getOrDefault(emptyList()).filter { it.url.startsWith("http") }

    fun linksOf(item: TsItem): List<TsLink> =
        playable(item).distinctBy { it.url }.map { TsLink(it.name.orEmpty().trim(), it.url!!.trim()) }

    fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /**
     * Whether a stream URL is one of the site's own player pages (`https://ehgembxe.cfd/<8 chars>-<4 digits>`), which
     * need a browser to find the playlist. Anything else (the replays' voe.sx) goes to the extractors.
     */
    fun isPlayerEmbed(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return Regex("""^/[A-Za-z0-9]{6,12}-\d{3,6}/?$""").matches(path) || hostOf(url)?.startsWith("ehgembxe.") == true
    }

    // ---- playlists --------------------------------------------------------------------------------------------

    /** What the WebView has to see go by: the master playlist of the stream origin. */
    val MASTER_SNIFF = Regex("""sytes\.net/main/secure/.*\.m3u8""")

    class Variant(val url: String, val height: Int?, val bandwidth: Long)

    /** The highest-bandwidth variant of a master playlist, its URI resolved against [masterUrl]. */
    fun bestVariant(master: String, masterUrl: String): Variant? {
        val lines = master.lines().map { it.trim() }
        var best: Variant? = null
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val variant = Variant(resolve(masterUrl, uri) ?: continue, height(line), bandwidth)
            if (best == null || variant.bandwidth > best.bandwidth) best = variant
        }
        return best
    }

    /** Height from `RESOLUTION=1920x1080`; the site also writes a bare `RESOLUTION=720`. */
    fun height(streamInf: String): Int? {
        val m = Regex("""RESOLUTION=(\d+)(?:x(\d+))?""").find(streamInf) ?: return null
        return (m.groupValues[2].ifEmpty { m.groupValues[1] }).toIntOrNull()
    }

    fun resolve(base: String, ref: String): String? = runCatching { URI(base).resolve(ref).toString() }.getOrNull()

    /**
     * The media playlist with every segment URI replaced by `[segmentBase]?u=<original, absolute>`. Everything else
     * (sequence number, durations) is kept, so the player's live window stays in step with the origin.
     */
    fun rewriteMedia(playlist: String, playlistUrl: String, segmentBase: String): String =
        playlist.lines().joinToString("\n") { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) line
            else resolve(playlistUrl, line)?.let { "$segmentBase?u=" + URLEncoder.encode(it, "UTF-8") } ?: line
        }

    /** Absolute segment URLs in playlist order. */
    fun segmentUrls(playlist: String, playlistUrl: String): List<String> =
        playlist.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { resolve(playlistUrl, it) }

    /** The `u` query parameter of a loopback request target, decoded. */
    fun queryU(target: String): String? =
        Regex("""[?&]u=([^&]*)""").find(target)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
}
