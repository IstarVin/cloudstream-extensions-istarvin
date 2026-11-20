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
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * tim-streams.com is a React SPA over plain JSON (`/api/live-upcoming`, `/api/channels`, `/api/replays`). Events and
 * channels play from an embed whose player computes the signed playlist URL in WASM, so a hidden WebView loads the
 * embed and we take the playlist URL it requests; the segments are PNG images with MPEG-TS inside, unwrapped by
 * [TimStreamsLocalServer]. Replays are voe.sx embeds handed to the extractors.
 */
class TimStreams : MainAPI() {
    override var mainUrl = "https://tim-streams.com"
    override var name = "TimStreams"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    // Every row comes from one call that returns all of them: the genres are data, not a fixed list.
    override val mainPage = mainPageOf("home" to "Home")

    private class Catalog(val events: TsEvents?, val channels: TsChannels?, val replays: List<TsItem>?, val at: Long)

    private val catalogLock = Mutex()
    @Volatile private var catalog: Catalog? = null

    private suspend fun catalog(): Catalog {
        catalog?.takeIf { System.currentTimeMillis() - it.at < CATALOG_TTL_MS }?.let { return it }
        return catalogLock.withLock {
            catalog?.takeIf { System.currentTimeMillis() - it.at < CATALOG_TTL_MS }?.let { return@withLock it }
            val events = fetch("live-upcoming")?.let(TimStreamsParser::events)
            val channels = fetch("channels")?.let(TimStreamsParser::channels)
            val replays = fetch("replays")?.let(TimStreamsParser::replays)?.replays
            if (events == null && channels == null && replays == null) {
                catalog ?: throw ErrorLoadingException("tim-streams.com returned no catalog")
            } else {
                // Keep the last good copy of a list that failed this time.
                val old = catalog
                Catalog(events ?: old?.events, channels ?: old?.channels, replays ?: old?.replays, System.currentTimeMillis())
                    .also { catalog = it }
            }
        }
    }

    private suspend fun fetch(endpoint: String): String? = try {
        app.get("$mainUrl/api/$endpoint", headers = mapOf("Accept" to "application/json")).text
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(name, "$endpoint: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val c = catalog()
        val lists = mutableListOf<HomePageList>()
        c.events?.let { events ->
            TimStreamsParser.eventRows(events).forEach { (title, items) ->
                lists += HomePageList(title, items.map { it.toCard(TimStreamsParser.KIND_EVENT, true) }, isHorizontalImages = true)
            }
        }
        c.channels?.let { channels ->
            TimStreamsParser.channelRows(channels).forEach { (title, items) ->
                lists += HomePageList(title, items.map { it.toCard(TimStreamsParser.KIND_CHANNEL, false) }, isHorizontalImages = true)
            }
        }
        c.replays?.filter(TimStreamsParser::isListed)?.takeIf { it.isNotEmpty() }?.let { replays ->
            lists += HomePageList("Replays", replays.map { it.toCard(TimStreamsParser.KIND_REPLAY, false) }, isHorizontalImages = true)
        }
        if (lists.isEmpty()) throw ErrorLoadingException("tim-streams.com has nothing live right now")
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty() || page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val c = catalog()
        val hits = mutableListOf<SearchResponse>()
        c.events?.let { events ->
            events.events.filter(TimStreamsParser::isListed).filter {
                TimStreamsParser.matches(it, TimStreamsParser.genreName(events.genres, it.genre), q)
            }.mapTo(hits) { it.toCard(TimStreamsParser.KIND_EVENT, true) }
        }
        c.channels?.let { channels ->
            channels.channels.filter(TimStreamsParser::isListed).filter {
                TimStreamsParser.matches(it, channels.genres.firstOrNull { g -> g.id == it.genre }?.name, q)
            }.mapTo(hits) { it.toCard(TimStreamsParser.KIND_CHANNEL, false) }
        }
        c.replays?.filter(TimStreamsParser::isListed)?.filter { TimStreamsParser.matches(it, "replay", q) }
            ?.mapTo(hits) { it.toCard(TimStreamsParser.KIND_REPLAY, false) }
        return newSearchResponseList(hits, hasNext = false)
    }

    private fun TsItem.toCard(kind: String, withClock: Boolean): SearchResponse =
        newLiveSearchResponse(
            if (withClock) TimStreamsParser.eventTitle(this) else name.orEmpty().trim(),
            TimStreamsParser.pageUrl(mainUrl, kind, url!!),
            TvType.Live
        ) {
            posterUrl = TimStreamsParser.posterUrl(logo, mainUrl)
        }

    override suspend fun load(url: String): LoadResponse {
        val (kind, slug) = TimStreamsParser.pageRef(url) ?: throw ErrorLoadingException("Not a tim-streams.com page: $url")
        val c = catalog()
        val pool = when (kind) {
            TimStreamsParser.KIND_EVENT -> c.events?.events
            TimStreamsParser.KIND_CHANNEL -> c.channels?.channels
            else -> c.replays
        }
        var item = pool?.firstOrNull { it.url == slug }
        var genreLabel: String? = when (kind) {
            TimStreamsParser.KIND_EVENT -> item?.let { TimStreamsParser.genreName(c.events!!.genres, it.genre) }
            TimStreamsParser.KIND_CHANNEL -> item?.let { i -> c.channels!!.genres.firstOrNull { it.id == i.genre }?.name }
            else -> "Replay"
        }
        if (item == null) {
            // Gone from the list (an event that ended): the site still knows the slug for a while.
            val watch = fetch("watch/$slug")?.let(TimStreamsParser::watch)
            item = watch?.item
            genreLabel = watch?.genreName
        }
        if (item == null) throw ErrorLoadingException("\"$slug\" is no longer listed on tim-streams.com")
        val links = TimStreamsParser.linksOf(item)
        if (links.isEmpty()) throw ErrorLoadingException("No free stream for this item (VIP only)")

        val title = item.name.orEmpty().trim()
        val subGenre = if (kind == TimStreamsParser.KIND_EVENT) {
            TimStreamsParser.subGenreName(c.events?.genres.orEmpty(), item.genre, item.subGenre)
        } else null
        val plot = buildString {
            when (kind) {
                TimStreamsParser.KIND_EVENT -> TimStreamsParser.dateTime(item.time)?.let {
                    append("Scheduled $it (the site's own clock, probably US Eastern).")
                }
                TimStreamsParser.KIND_CHANNEL -> append("24/7 channel.")
                else -> item.date?.let { append("Replay from $it.") }
            }
            if (links.size > 1) append(" ${links.size} sources: ${links.joinToString { it.name.ifBlank { "Stream" } }}.")
        }.trim()
        return newLiveStreamLoadResponse(title, url, TimStreamsParser.linksJson(links)) {
            posterUrl = TimStreamsParser.posterUrl(item.logo, mainUrl)
            this.plot = plot.ifEmpty { null }
            tags = listOfNotNull(genreLabel, subGenre)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = TimStreamsParser.links(data).ifEmpty { return false }
        val found = AtomicBoolean(false)
        // A WebView per stream: two at a time is plenty, and the player waits for the slowest one.
        val browsers = Semaphore(2)
        runAllAsync(*links.map { link ->
            suspend {
                try {
                    if (TimStreamsParser.isPlayerEmbed(link.url)) {
                        val playlist = withTimeoutOrNull(EMBED_BUDGET_MS) { browsers.withPermit { resolveEmbed(link.url) } }
                        if (playlist != null) {
                            val label = link.name.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
                            callback(newExtractorLink(name, if (label == null) name else "$name $label", playlist.first, ExtractorLinkType.M3U8) {
                                quality = playlist.second ?: DEFAULT_QUALITY
                            })
                            found.set(true)
                        } else {
                            Log.w(name, "no playlist from ${link.url}")
                        }
                    } else if (loadExtractor(link.url, "$mainUrl/", subtitleCallback, callback)) {
                        found.set(true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "${link.url}: ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }.toTypedArray())
        return found.get()
    }

    /** (loopback playlist URL, height) of an embed page, or null when no playlist was found or it can't be read. */
    private suspend fun resolveEmbed(embed: String): Pair<String, Int?>? {
        val master = WebViewResolver(
            interceptUrl = TimStreamsParser.MASTER_SNIFF,
            userAgent = null,
            useOkhttp = false,
            timeout = SNIFF_TIMEOUT_MS
        ).resolveUsingWebView(embed).first?.url?.toString() ?: return null
        Log.i(name, "sniffed ${master.substringBefore("/secure/")}/secure/…/${master.substringAfterLast('/')}")

        val text = withContext(Dispatchers.IO) { TimStreamsLocalServer.fetchText(master) } ?: return null
        val variant = TimStreamsParser.bestVariant(text, master)
        // A single-bitrate stream may name the media playlist directly.
        val (media, height) = when {
            variant != null -> variant.url to variant.height
            text.contains("#EXTINF") -> master to null
            else -> return null
        }
        return TimStreamsLocalServer.playlistUrl(media) to height
    }

    companion object {
        private const val CATALOG_TTL_MS = 2 * 60_000L
        private const val SNIFF_TIMEOUT_MS = 30_000L
        private const val EMBED_BUDGET_MS = 40_000L
        private const val DEFAULT_QUALITY = 720
    }
}
