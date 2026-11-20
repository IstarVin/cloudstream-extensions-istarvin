package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure logic for ppv.st: the `/api/streams` list, live/upcoming classification, page URLs, embed lists and segment unwrapping. */
object PPVstParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    const val SITE = "https://ppv.st"
    const val API = "https://api.ppv.st/api/streams"
    private const val LIVE_PREFIX = "$SITE/live/"

    /** Cloudstream's `HH:mm` row prefix and the like use the phone's zone; tests pass a fixed one. */
    private val localZone: TimeZone get() = TimeZone.getDefault()

    /** The list envelope, or null when the body is not a successful `/api/streams` answer (the API returns HTTP 200 with `success:false` for errors). */
    fun parse(json: String): PpvEnvelope? =
        runCatching { mapper.readValue<PpvEnvelope>(json) }.getOrNull()?.takeIf { it.success && it.streams.isNotEmpty() }

    /** Every stream once, in list order, with the category it was first listed under. Streams without id, name or uri are dropped. */
    fun entries(envelope: PpvEnvelope): List<PpvEntry> {
        val seen = HashSet<Long>()
        return envelope.streams.flatMap { c ->
            c.streams.mapNotNull { s ->
                val id = s.id ?: return@mapNotNull null
                if (s.name.isNullOrBlank() || s.uriName.isNullOrBlank() || !seen.add(id)) return@mapNotNull null
                PpvEntry(s, c.category.orEmpty().ifBlank { s.categoryName.orEmpty() })
            }
        }
    }

    fun isAlwaysLive(s: PpvStream): Boolean = when (val v = s.alwaysLive) {
        is Number -> v.toInt() != 0
        is Boolean -> v
        is String -> v == "1" || v.equals("true", true)
        else -> false
    }

    fun viewers(s: PpvStream): Int = when (val v = s.viewers) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }

    /** A timed event is live between its start and end (server clock [now], unix seconds). 24/7 channels are not events. */
    fun isLive(s: PpvStream, now: Long): Boolean {
        if (isAlwaysLive(s)) return false
        val start = s.startsAt ?: return false
        val end = s.endsAt ?: return false
        return start > 0 && now in start..end
    }

    fun isUpcoming(s: PpvStream, now: Long): Boolean =
        !isAlwaysLive(s) && (s.startsAt ?: 0) > now

    fun live(entries: List<PpvEntry>, now: Long): List<PpvEntry> =
        entries.filter { isLive(it.stream, now) }.sortedByDescending { viewers(it.stream) }

    fun upcoming(entries: List<PpvEntry>, now: Long): List<PpvEntry> =
        entries.filter { isUpcoming(it.stream, now) }.sortedBy { it.stream.startsAt }

    fun alwaysLive(entries: List<PpvEntry>): List<PpvEntry> =
        entries.filter { isAlwaysLive(it.stream) }.sortedByDescending { viewers(it.stream) }

    /** Entries of one category row, in server order. */
    fun inCategory(entries: List<PpvEntry>, category: String): List<PpvEntry> =
        entries.filter { it.category.equals(category, ignoreCase = true) }

    /** Entries whose category is none of [known], so a sport the site adds later still shows up. */
    fun otherCategories(entries: List<PpvEntry>, known: Collection<String>): List<PpvEntry> =
        entries.filter { e -> known.none { it.equals(e.category, ignoreCase = true) } }

    /** Case-insensitive contains on name, league tag and category; every word of the query must match somewhere. */
    fun search(entries: List<PpvEntry>, query: String): List<PpvEntry> {
        val words = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { e ->
            val hay = listOf(e.stream.name, e.stream.tag, e.category).joinToString(" ") { it.orEmpty() }.lowercase()
            words.all { it in hay }
        }
    }

    fun pageUrl(uriName: String): String = LIVE_PREFIX + uriName

    /** The `uri_name` (it may contain slashes) of a `https://ppv.st/live/...` URL. */
    fun uriOf(url: String): String? =
        url.substringAfter("/live/", "").substringBefore('?').substringBefore('#').trim('/').ifEmpty { null }

    /** Local `HH:mm`, with the weekday when the start is not on [now]'s day. */
    fun timeLabel(startsAt: Long, now: Long, zone: TimeZone = localZone): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }
        val sameDay = day.format(Date(startsAt * 1000)) == day.format(Date(now * 1000))
        val pattern = if (sameDay) "HH:mm" else "EEE HH:mm"
        return SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(startsAt * 1000))
    }

    /** Card title: upcoming events carry their start time, everything else is the plain name. */
    fun title(s: PpvStream, now: Long, zone: TimeZone = localZone): String {
        val name = s.name.orEmpty().trim()
        return if (isUpcoming(s, now)) "${timeLabel(s.startsAt!!, now, zone)}  $name" else name
    }

    fun plot(e: PpvEntry, now: Long, zone: TimeZone = localZone): String {
        val s = e.stream
        val status = when {
            isAlwaysLive(s) -> "24/7 channel"
            isLive(s, now) -> "Live now"
            isUpcoming(s, now) -> "Starts ${timeLabel(s.startsAt!!, now, zone)}"
            else -> "Ended"
        }
        val viewers = viewers(s).takeIf { it > 0 }?.let { "$it viewers" }
        return listOfNotNull(s.tag?.ifBlank { null }, e.category.ifBlank { null }, status, s.sourceTag?.ifBlank { null }, viewers)
            .distinct().joinToString(" · ")
    }

    /** `HD | ABC` is 720, `SD | ABC` 540; anything else is unknown (0). */
    fun quality(label: String): Int = when (label.substringBefore('|').trim().uppercase()) {
        "HD", "FHD" -> 720
        "SD" -> 540
        else -> 0
    }

    private fun isHttp(url: String?) = url != null && (url.startsWith("https://") || url.startsWith("http://"))

    /** The main feed first, then every substream, with full iframe URLs and no duplicates. Labels fall back to "Feed N". */
    fun embeds(s: PpvStream): List<PpvEmbed> {
        val all = buildList {
            if (isHttp(s.iframe)) add(PpvEmbed(s.sourceTag.orEmpty().trim(), s.iframe!!))
            s.substreams.forEach { if (isHttp(it.iframe)) add(PpvEmbed(it.sourceTag.orEmpty().trim(), it.iframe!!)) }
        }.distinctBy { it.url }
        return all.mapIndexed { i, e -> if (e.label.isBlank()) e.copy(label = "Feed ${i + 1}") else e }
    }

    fun dataJson(s: PpvStream): String =
        mapper.writeValueAsString(PpvData(s.uriName.orEmpty(), embeds(s)))

    fun data(json: String): PpvData? =
        runCatching { mapper.readValue<PpvData>(json) }.getOrNull()?.takeIf { it.embeds.isNotEmpty() }

    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    /** `indianservers.st/secure/<token>/.../index.m3u8`, the playlist the embed player requests. */
    val playlistRegex = Regex("""indianservers\.st/secure/[^?#\s]*\.m3u8""")

    fun isPlaylistUrl(url: String): Boolean = playlistRegex.containsMatchIn(url)

    /** Ad and tracker hosts the embed page pulls in; requests to them are not needed for the player. */
    private val adHosts = listOf(
        "adexchangeclear.com", "usrpubtrk.com", "aclib", "popads", "popcash", "propellerads", "exoclick", "juicyads",
        "histats.com", "googletagmanager.com", "google-analytics.com", "doubleclick.net", "mumbai.television.st",
    )

    fun isAdUrl(url: String): Boolean {
        val host = hostOf(url)?.lowercase() ?: return false
        return adHosts.any { host.contains(it) }
    }

    // MPEG-TS packets are 188 bytes and start with 0x47.
    private const val TS_PACKET = 188
    private const val TS_SYNC = 0x47.toByte()

    /**
     * Offset of the first MPEG-TS packet. The CDN wraps segments as images: a short WebP/RIFF header precedes plain TS.
     * A real sync byte is followed by another one a packet later (and two), so the scan does not stop on a 0x47 inside
     * the header. Returns 0 when the data is already TS, or -1 when no packet train is found.
     */
    fun tsOffset(bytes: ByteArray, scanLimit: Int = 4096): Int {
        val last = minOf(bytes.size, scanLimit)
        for (i in 0 until last) {
            if (bytes[i] != TS_SYNC) continue
            val second = i + TS_PACKET
            val third = i + 2 * TS_PACKET
            if (second >= bytes.size) return -1
            if (bytes[second] != TS_SYNC) continue
            if (third < bytes.size && bytes[third] != TS_SYNC) continue
            return i
        }
        return -1
    }

    /** The segment with any non-TS prefix removed; unrecognised data is returned unchanged. */
    fun unwrapSegment(bytes: ByteArray): ByteArray {
        val offset = tsOffset(bytes)
        return if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
    }
}
