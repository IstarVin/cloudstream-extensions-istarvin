package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * sportsbite.org is a React SPA over a documented public JSON API (`/api/schedule`). Fixtures carry 0..N "selfhost" streams whose
 * `https://embed.sportsbite.org/selfhost/play/<id>` answers a plain HLS master playlist (raw TS segments, no headers).
 * The 24/7 channel feed (PNG-wrapped segments) is not used.
 */
class SportsBite : MainAPI() {
    override var mainUrl = "https://sportsbite.org"
    override var name = "SportsBite"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val scheduleLock = Mutex()
    @Volatile private var cache: Pair<List<SbEvent>, Long>? = null

    override val mainPage = mainPageOf(
        "live" to "Live Now",
        "football" to "Football",
        "basketball" to "Basketball",
        "american-football" to "American Football",
        "fighting" to "Fighting",
        "motorsports" to "Motorsports",
        "other" to "Other Sports",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = if (page > 1) emptyList() else {
            val all = schedule()
            val rows = when (request.data) {
                "live" -> all.filter { SportsBiteParser.isLive(it) }
                "other" -> all.filter { it.sport !in NAMED_SPORTS }
                else -> all.filter { it.sport == request.data }
            }
            SportsBiteParser.ordered(rows).take(ROW_LIMIT).map { it.toSearchResponse() }
        }
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = HORIZONTAL), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank() || page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val hits = SportsBiteParser.ordered(schedule().filter { SportsBiteParser.matches(it, query) }).take(SEARCH_LIMIT)
        return newSearchResponseList(hits.map { it.toSearchResponse() }, hasNext = false)
    }

    private fun SbEvent.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(SportsBiteParser.cardTitle(this, TimeZone.getDefault()), SportsBiteParser.watchUrl(mainUrl, this), TvType.Live) {
            posterUrl = SportsBiteParser.poster(this@toSearchResponse)
        }

    override suspend fun load(url: String): LoadResponse {
        val slug = SportsBiteParser.slugOf(url) ?: throw ErrorLoadingException("Not a sportsbite.org match URL: $url")
        val event = schedule().firstOrNull { it.slug == slug }
            ?: schedule(force = true).firstOrNull { it.slug == slug }
            ?: throw ErrorLoadingException("This match is no longer listed on sportsbite.org (finished or removed)")
        return newLiveStreamLoadResponse(event.title.orEmpty(), SportsBiteParser.watchUrl(mainUrl, event), SportsBiteParser.dataJson(event)) {
            posterUrl = SportsBiteParser.poster(event)
            plot = SportsBiteParser.plot(event, TimeZone.getDefault())
            tags = listOfNotNull(SportsBiteParser.sportLabel(event.sport), event.tournament?.name?.ifBlank { null })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = SportsBiteParser.data(data) ?: return false
        var ids = parsed.streams
        // Streams are assigned shortly before kickoff, so a list saved earlier may be empty.
        if (ids.isEmpty()) ids = freshStreamIds(parsed.slug)
        if (ids.isEmpty()) return false
        var found = tryStreams(ids, callback)
        if (!found) {
            // The ids saved by `load` may be stale (reassigned); retry once with the current ones.
            val fresh = freshStreamIds(parsed.slug).filter { it !in ids }
            if (fresh.isNotEmpty()) found = tryStreams(fresh, callback)
        }
        return found
    }

    private suspend fun freshStreamIds(slug: String): List<String> =
        if (slug.isBlank()) emptyList()
        else schedule(force = true).firstOrNull { it.slug == slug }?.let { SportsBiteParser.streamIds(it) }.orEmpty()

    private class Probe(val index: Int, val url: String, val height: Int, val window: SportsBiteParser.Window)

    /**
     * Probes every server in parallel and emits the ones that answer a playlist, steadiest first; 503 "channel warming" and
     * timeouts are skipped. The media playlist is read too, to rank servers by the length of their live window.
     */
    private suspend fun tryStreams(ids: List<String>, callback: (ExtractorLink) -> Unit): Boolean {
        val probes = CopyOnWriteArrayList<Probe>()
        runAllAsync(*ids.mapIndexed { index, id ->
            suspend {
                val url = SportsBiteParser.playUrl(EMBED_BASE, id)
                try {
                    withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                        val res = app.get(url)
                        if (res.isSuccessful && SportsBiteParser.isPlaylist(res.text)) {
                            val window = SportsBiteParser.variantUrl(res.text, url)?.let { media ->
                                // A failed media read only costs the ranking, not the link.
                                try {
                                    SportsBiteParser.window(app.get(media).text)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                            } ?: SportsBiteParser.Window(0.0, 0)
                            Log.i(name, "$id: window ${window.seconds}s target ${window.target}")
                            probes.add(Probe(index, url, SportsBiteParser.height(res.text), window))
                        } else {
                            Log.w(name, "$id: HTTP ${res.code}")
                        }
                    } ?: Log.i(name, "$id: timed out")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "$id: ${e.message}")
                }
            }
        }.toTypedArray())
        SportsBiteParser.rank(probes.toList(), { it.height }, { it.window }).forEach { p ->
            callback(newExtractorLink(name, "$name Server ${p.index + 1}", p.url, ExtractorLinkType.M3U8) {
                quality = p.height.takeIf { it > 0 } ?: Qualities.Unknown.value
            })
        }
        return probes.isNotEmpty()
    }

    /** The whole schedule (all pages of 100), cached for a minute because the API allows 60 requests per minute per IP. */
    private suspend fun schedule(force: Boolean = false): List<SbEvent> {
        fun fresh() = cache?.takeIf { System.currentTimeMillis() - it.second < (if (force) FORCE_TTL_MS else SCHEDULE_TTL_MS) }?.first
        fresh()?.let { return it }
        return scheduleLock.withLock {
            fresh()?.let { return@withLock it }
            try {
                val events = mutableListOf<SbEvent>()
                var page = 1
                while (page <= MAX_PAGES) {
                    val res = app.get("$mainUrl/api/schedule?limit=100&page=$page")
                    val parsed = SportsBiteParser.schedule(res.text)
                        ?: throw ErrorLoadingException("sportsbite.org schedule unavailable (HTTP ${res.code})")
                    events += parsed.events
                    if (parsed.events.isEmpty() || page >= parsed.pagination.pages) break
                    page++
                }
                SportsBiteParser.usable(events).also { cache = it to System.currentTimeMillis() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "schedule: ${e.message}")
                cache?.first ?: throw (e as? ErrorLoadingException ?: ErrorLoadingException("sportsbite.org schedule failed: ${e.message}"))
            }
        }
    }

    companion object {
        private const val EMBED_BASE = "https://embed.sportsbite.org"
        private const val HORIZONTAL = true
        private const val ROW_LIMIT = 60
        private const val SEARCH_LIMIT = 60
        private const val MAX_PAGES = 5
        private const val SCHEDULE_TTL_MS = 60_000L
        private const val FORCE_TTL_MS = 10_000L
        private const val STREAM_TIMEOUT_MS = 8_000L
        private val NAMED_SPORTS = setOf("football", "basketball", "american-football", "fighting", "motorsports")
    }
}
