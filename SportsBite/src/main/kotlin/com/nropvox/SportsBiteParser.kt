package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure parsing for sportsbite.org: the public schedule JSON, the `data` JSON handed to `loadLinks`, labels and playlist hints. */
object SportsBiteParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** One page of `/api/schedule`, or null when the body is not that JSON (an `{"error":...}` body has no `events`). */
    fun schedule(json: String): SbSchedule? =
        if (!json.contains("\"events\"")) null else runCatching { mapper.readValue<SbSchedule>(json) }.getOrNull()

    /** Events that have an id, a slug and a title: the only ones that can be shown and found again. */
    fun usable(events: List<SbEvent>): List<SbEvent> =
        events.filter { !it.id.isNullOrBlank() && !it.slug.isNullOrBlank() && !it.title.isNullOrBlank() }

    fun isLive(event: SbEvent): Boolean = event.status == "live"

    /** Live first, then by kickoff. */
    fun ordered(events: List<SbEvent>): List<SbEvent> =
        events.sortedWith(compareByDescending<SbEvent> { isLive(it) }.thenBy { it.timestamp ?: Long.MAX_VALUE })

    /** `american-football` -> `American Football`. */
    fun sportLabel(sport: String?): String =
        sport.orEmpty().trim().split('-', '_', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .ifEmpty { "Other" }

    /** `streams[].embed_url` ends in the stream id; falls back to the stream's own `id`. */
    fun streamId(stream: SbStream): String? =
        stream.embedUrl?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.startsWith("s-") }
            ?: stream.id?.takeIf { it.isNotBlank() }

    fun streamIds(event: SbEvent): List<String> = event.streams.mapNotNull { streamId(it) }.distinct()

    fun watchUrl(mainUrl: String, event: SbEvent): String = event.watchUrl?.takeIf { it.isNotBlank() } ?: "$mainUrl/watch/${event.slug}"

    /** The slug of a `/watch/<slug>` (or `/match/<slug>`) URL; a bare slug is accepted too. */
    fun slugOf(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/')
        return last.takeIf { it.isNotBlank() && !it.contains('.') }
    }

    fun dataJson(event: SbEvent): String =
        mapper.writeValueAsString(SbData(event.id.orEmpty(), event.slug.orEmpty(), streamIds(event)))

    fun data(json: String): SbData? =
        if (!json.trimStart().startsWith("{")) null
        else runCatching { mapper.readValue<SbData>(json) }.getOrNull()?.takeIf { it.slug.isNotBlank() || it.streams.isNotEmpty() }

    fun playUrl(embedBase: String, streamId: String): String = "$embedBase/selfhost/play/$streamId"

    /** Square team or league logo for the card: home, then the tournament, then away. */
    fun poster(event: SbEvent): String? =
        listOf(event.teams?.home?.logo, event.tournament?.logo, event.teams?.away?.logo).firstOrNull { !it.isNullOrBlank() }

    /** `Fri 20 Nov 09:00` in [tz]. */
    fun kickoff(timestamp: Long, tz: TimeZone): String =
        SimpleDateFormat("EEE d MMM HH:mm", Locale.ENGLISH).apply { timeZone = tz }.format(Date(timestamp))

    /** Card title: live matches as is, upcoming ones prefixed with their kickoff time (`09:00`). */
    fun cardTitle(event: SbEvent, tz: TimeZone): String {
        val title = event.title.orEmpty().trim()
        val ts = event.timestamp
        if (isLive(event) || ts == null) return title
        val time = SimpleDateFormat("HH:mm", Locale.ENGLISH).apply { timeZone = tz }.format(Date(ts))
        return "$time $title"
    }

    fun plot(event: SbEvent, tz: TimeZone): String {
        val parts = mutableListOf<String>()
        event.tournament?.name?.takeIf { it.isNotBlank() }?.let(parts::add)
        parts.add(sportLabel(event.sport))
        val ts = event.timestamp
        when {
            isLive(event) -> parts.add("Live now")
            ts != null -> parts.add(kickoff(ts, tz) + " " + tz.getDisplayName(tz.inDaylightTime(Date(ts)), TimeZone.SHORT, Locale.ENGLISH))
        }
        if (streamIds(event).isEmpty()) parts.add("No stream assigned yet")
        return parts.joinToString(" · ")
    }

    /** Case-insensitive match on the title, both team names, the tournament and the sport. */
    fun matches(event: SbEvent, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        return listOf(event.title, event.teams?.home?.name, event.teams?.away?.name, event.tournament?.name, sportLabel(event.sport))
            .any { it?.contains(q, ignoreCase = true) == true }
    }

    /** True for a body that is an HLS playlist. */
    fun isPlaylist(body: String): Boolean = body.trimStart().startsWith("#EXTM3U")

    /** The highest `RESOLUTION=WxH` height of a master playlist; 0 when unknown. */
    fun height(master: String): Int =
        Regex("""RESOLUTION=\d+x(\d+)""").findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0

    /** The media playlist URL named by a master (relative or absolute), resolved against [playUrl]; null when it has no variant. */
    fun variantUrl(master: String, playUrl: String): String? {
        val line = master.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return runCatching { java.net.URI(playUrl).resolve(line).toString() }.getOrNull()
    }

    /** Length of a live media playlist's window in seconds and its target duration; zeros when it is not a playlist. */
    data class Window(val seconds: Double, val target: Int)

    fun window(media: String): Window {
        if (!isPlaylist(media)) return Window(0.0, 0)
        val seconds = Regex("""#EXTINF:([\d.]+)""").findAll(media).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        val target = Regex("""#EXT-X-TARGETDURATION:(\d+)""").find(media)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Window(seconds, target)
    }

    /**
     * Order for emitting servers: higher resolution first, then the longest live window. The player stalls with
     * `PlaylistStuckException` (no update within 3.5 target durations) or `BehindLiveWindowException` (the sequence jumps by
     * more than the window) on feeds whose window is short, and the upstream playlists update in bursts.
     */
    fun <T> rank(items: List<T>, height: (T) -> Int, window: (T) -> Window): List<T> =
        items.sortedWith(compareByDescending<T> { height(it) }.thenByDescending { window(it).seconds })
}
