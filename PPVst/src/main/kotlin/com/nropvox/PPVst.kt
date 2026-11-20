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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ppv.st is a Nuxt SPA over one JSON list (`api.ppv.st/api/streams`: sports categories, events, 24/7 channels). Its
 * players are third-party embed pages that decrypt the HLS URL in wasm, so `loadLinks` loads each embed in a WebView and
 * takes the indianservers.st secure m3u8 request the player makes; the app's player then fetches it itself.
 */
class PPVst : MainAPI() {
    override var mainUrl = PPVstParser.SITE
    override var name = "PPVst"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val listLock = Mutex()
    @Volatile private var cached: Snapshot? = null

    private class Snapshot(val entries: List<PpvEntry>, val serverTime: Long, val fetchedAt: Long) {
        /** Server clock now, in unix seconds, so a skewed phone clock does not misclassify events. */
        fun now(): Long = serverTime + (System.currentTimeMillis() - fetchedAt) / 1000
    }

    override val mainPage = mainPageOf(
        "live" to "Live now",
        "upcoming" to "Upcoming",
        "247" to "24/7 Streams",
        *CATEGORIES.map { "cat:$it" to it }.toTypedArray(),
        "other" to "More sports",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val snap = snapshot()
        val now = snap.now()
        val entries = snap.entries
        val items = when {
            request.data == "live" -> PPVstParser.live(entries, now)
            request.data == "upcoming" -> PPVstParser.upcoming(entries, now).take(UPCOMING_LIMIT)
            request.data == "247" -> PPVstParser.alwaysLive(entries)
            request.data == "other" -> PPVstParser.otherCategories(entries, CATEGORIES + ALWAYS_LIVE_CATEGORY)
            request.data.startsWith("cat:") -> PPVstParser.inCategory(entries, request.data.removePrefix("cat:"))
            else -> emptyList()
        }
        return newHomePageResponse(
            HomePageList(request.name, if (page > 1) emptyList() else items.map { it.toSearchResponse(now) }, isHorizontalImages = true),
            hasNext = false
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank() || page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val snap = snapshot()
        val now = snap.now()
        return newSearchResponseList(PPVstParser.search(snap.entries, query).map { it.toSearchResponse(now) }, hasNext = false)
    }

    private fun PpvEntry.toSearchResponse(now: Long): SearchResponse =
        newLiveSearchResponse(PPVstParser.title(stream, now), PPVstParser.pageUrl(stream.uriName!!), TvType.Live) {
            posterUrl = stream.poster?.ifBlank { null }
        }

    override suspend fun load(url: String): LoadResponse {
        val uri = PPVstParser.uriOf(url) ?: throw ErrorLoadingException("Not a ppv.st stream URL: $url")
        val snap = snapshot()
        val entry = snap.entries.firstOrNull { it.stream.uriName == uri }
            ?: throw ErrorLoadingException("This stream is no longer listed on ppv.st (events disappear once they end)")
        val s = entry.stream
        if (PPVstParser.embeds(s).isEmpty()) throw ErrorLoadingException("ppv.st lists no player for this stream")
        return newLiveStreamLoadResponse(s.name.orEmpty().trim(), url, PPVstParser.dataJson(s)) {
            posterUrl = s.poster?.ifBlank { null }
            plot = PPVstParser.plot(entry, snap.now())
            tags = listOfNotNull(s.tag?.ifBlank { null }, entry.category.ifBlank { null }).distinct()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = PPVstParser.data(data) ?: return false
        // The embed host rotates, so prefer the live list over the URLs stored when the page was loaded.
        val fresh = runCatching { snapshot().entries.firstOrNull { it.stream.uriName == parsed.uri } }.getOrNull()
            ?.let { PPVstParser.embeds(it.stream) }?.takeIf { it.isNotEmpty() }
        val embeds = (fresh ?: parsed.embeds).take(MAX_EMBEDS)
        val gate = Semaphore(PARALLEL_WEBVIEWS)
        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        coroutineScope {
            embeds.map { embed ->
                async {
                    val playlist = gate.withPermit {
                        try {
                            withTimeoutOrNull(EMBED_TIMEOUT_MS) { resolveEmbed(embed.url) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(name, "${embed.url}: ${e.javaClass.simpleName} ${e.message}")
                            null
                        }
                    }
                    if (playlist != null) {
                        Log.i(name, "${embed.label}: ${playlist.substringBefore("/secure/")}/secure/...")
                        callback(newExtractorLink(name, "$name ${embed.label}".trim(), playlist, ExtractorLinkType.M3U8) {
                            // No Referer: the CDN answers 403 to https://ppv.st/.
                            referer = ""
                            quality = PPVstParser.quality(embed.label)
                            headers = mapOf("User-Agent" to PLAYER_UA)
                        })
                        found.set(true)
                    } else {
                        Log.w(name, "${embed.label}: no playlist from ${embed.url}")
                    }
                }
            }.awaitAll()
        }
        return found.get()
    }

    /**
     * Plays the embed page in a hidden WebView ([PPVstBrowser]) and returns the playlist URL its player requests. The
     * token in that URL is bound to the first client that fetches it, so the URL is never fetched here.
     */
    private suspend fun resolveEmbed(embed: String): String? {
        return PPVstBrowser.sniffPlaylist(embed, EMBED_TIMEOUT_MS - 1_000L)
    }

    /** The cached list, refreshed after [LIST_TTL_MS]. A failed refresh falls back to the stale list. */
    private suspend fun snapshot(): Snapshot {
        cached?.takeIf { System.currentTimeMillis() - it.fetchedAt < LIST_TTL_MS }?.let { return it }
        return listLock.withLock {
            cached?.takeIf { System.currentTimeMillis() - it.fetchedAt < LIST_TTL_MS }?.let { return@withLock it }
            val fetched = try {
                val body = app.get(PPVstParser.API, headers = mapOf("User-Agent" to PLAYER_UA), timeout = 20L).text
                PPVstParser.parse(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "list: ${e.javaClass.simpleName} ${e.message}")
                null
            }
            if (fetched == null) {
                cached ?: throw ErrorLoadingException("ppv.st returned no stream list")
            } else {
                Snapshot(PPVstParser.entries(fetched), fetched.timestamp, System.currentTimeMillis()).also { cached = it }
            }
        }
    }

    companion object {
        private const val ALWAYS_LIVE_CATEGORY = "24/7 Streams"
        private val CATEGORIES = listOf(
            "American Football", "Basketball", "Football", "Ice Hockey", "Motorsports", "Wrestling", "Tennis", "Cricket",
            "Rugby", "Australian Football", "Darts", "Golf",
        )
        private const val LIST_TTL_MS = 60_000L
        private const val UPCOMING_LIMIT = 100
        private const val MAX_EMBEDS = 6
        private const val PARALLEL_WEBVIEWS = 3
        private const val EMBED_TIMEOUT_MS = 15_000L
        private const val PLAYER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
