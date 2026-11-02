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
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ntv.st is a Laravel/Inertia site over its own JSON (`/api/get-matches`, `/api/get-channels`) that lists the
 * embeds of four upstream servers. Only Titan matches and the cdnlive / Falcon channels resolve to plain HLS (Falcon through a
 * loopback playlist, see [NTVStreamLocalServer]);
 * Raptor (taifood-blog.asia), Phoenix (dlhd) and the embedsports matches need a browser and are left out.
 */
class NTVStream : MainAPI() {
    override var mainUrl = "https://ntv.st"
    override var name = "NTVStream"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val matchLock = Mutex()
    @Volatile private var matches: Pair<List<NtvMatch>, Long>? = null

    override val mainPage = mainPageOf(
        "live" to "Live Now",
        "soon" to "Upcoming",
        "channels" to "Channels",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val now = System.currentTimeMillis()
        return when (request.data) {
            "live" -> row(request, page, titanMatches().filter { NTVStreamParser.isLive(it, now) }.sortedBy { it.date })
            "soon" -> row(request, page, NTVStreamParser.upcoming(titanMatches(), now).take(UPCOMING_LIMIT))
            else -> {
                val list = channelPage(page, null)
                newHomePageResponse(
                    HomePageList(request.name, list.items.map { it.toSearchResponse() }, isHorizontalImages = true),
                    hasNext = list.hasMore
                )
            }
        }
    }

    private fun row(request: MainPageRequest, page: Int, items: List<NtvMatch>): HomePageResponse =
        newHomePageResponse(
            HomePageList(request.name, if (page > 1) emptyList() else items.map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = false
        )

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty() || page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val hits = titanMatches().filter { m ->
            listOf(m.title, m.category, m.tournament).any { it?.contains(q, ignoreCase = true) == true }
        }.take(SEARCH_LIMIT).map { it.toSearchResponse() } + channelPage(1, q).items.map { it.toSearchResponse() }
        return newSearchResponseList(hits, hasNext = false)
    }

    private fun NtvMatch.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(NTVStreamParser.cleanTitle(title.orEmpty()), NTVStreamParser.matchUrl(mainUrl, TITAN, id!!), TvType.Live)

    private fun NtvChannel.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(name!!, NTVStreamParser.channelUrl(mainUrl, this), TvType.Live) {
            posterUrl = image?.ifBlank { null }
        }

    override suspend fun load(url: String): LoadResponse {
        NTVStreamParser.matchRef(url)?.let { (server, id) ->
            val match = titanMatches().firstOrNull { server == TITAN && it.id == id }
                ?: throw ErrorLoadingException("Match $id is no longer listed on ntv.st (they disappear once over)")
            val links = NTVStreamParser.playable(match).map { NtvLink(it.channelName ?: it.source.orEmpty(), it.url!!) }
            if (links.isEmpty()) throw ErrorLoadingException("No playable source for this match")
            return newLiveStreamLoadResponse(NTVStreamParser.cleanTitle(match.title.orEmpty()), url, NTVStreamParser.linksJson(links)) {
                plot = NTVStreamParser.matchPlot(match, System.currentTimeMillis())
                tags = listOfNotNull(match.category?.ifBlank { null }, match.tournament?.ifBlank { null })
            }
        }
        val (embed, channelName) = NTVStreamParser.channelRef(url) ?: throw ErrorLoadingException("Not an ntv.st match or channel URL: $url")
        val title = channelName.ifBlank { "Channel" }
        return newLiveStreamLoadResponse(title, url, NTVStreamParser.linksJson(listOf(NtvLink(title, embed))))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = NTVStreamParser.links(data).ifEmpty { return false }
        val found = CopyOnWriteArrayList<Boolean>()
        runAllAsync(*links.map { link ->
            suspend {
                val playlist = try {
                    resolveEmbed(link.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "${link.url}: ${e.message}")
                    null
                }
                if (playlist != null) {
                    callback(newExtractorLink(name, "$name ${link.name}".trim(), playlist, ExtractorLinkType.M3U8) {
                        quality = NTVStreamParser.quality(link.name)
                    })
                    found.add(true)
                }
            }
        }.toTypedArray())
        return found.isNotEmpty()
    }

    /** The playlist URL of one embed page, or null when it cannot be resolved without a browser. */
    private suspend fun resolveEmbed(embed: String): String? = when (NTVStreamParser.hostOf(embed)) {
        "cdnlivetv.tv" -> NTVStreamParser.cdnlivePlaylist(app.get(embed, referer = "$mainUrl/").text)
        // The token on wideiptv.top's URLs lasts 5 minutes, so the player gets a loopback playlist that re-mints it.
        "hesgoal.team" -> NTVStreamParser.wideiptvChannel(embed)?.takeIf {
            withContext(Dispatchers.IO) { NTVStreamLocalServer.isLive(it) }
        }?.let(NTVStreamLocalServer::playlistUrl)
        else -> null
    }

    /** All Titan matches. Raptor, Falcon and Phoenix lists are not used: their embeds do not resolve. */
    private suspend fun titanMatches(): List<NtvMatch> {
        matches?.takeIf { System.currentTimeMillis() - it.second < MATCH_TTL_MS }?.let { return it.first }
        return matchLock.withLock {
            matches?.takeIf { System.currentTimeMillis() - it.second < MATCH_TTL_MS }?.let { return@withLock it.first }
            var list: NtvMatchList? = null
            // upstream_busy means the server-side cache is still warming.
            for (attempt in 0..2) {
                list = NTVStreamParser.matches(app.get("$mainUrl/api/get-matches?server=$TITAN&type=both").text)
                if (list != null && !(list.upstreamBusy && list.all.isEmpty())) break
                delay(2_000L * (attempt + 1))
            }
            val usable = list?.all.orEmpty().filter { !it.id.isNullOrBlank() && !it.title.isNullOrBlank() && NTVStreamParser.playable(it).isNotEmpty() }
            if (usable.isEmpty()) matches?.first ?: throw ErrorLoadingException("ntv.st returned no Titan matches")
            else usable.also { matches = it to System.currentTimeMillis() }
        }
    }

    private class ChannelPage(val items: List<NtvChannel>, val hasMore: Boolean)

    /** 50 channels of the merged list; the ones on unsupported servers are dropped client side. */
    private suspend fun channelPage(page: Int, query: String?): ChannelPage {
        val offset = (page - 1).coerceAtLeast(0) * CHANNEL_PAGE
        val q = query?.let { "&q=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val list = NTVStreamParser.channels(app.get("$mainUrl/api/get-channels?limit=$CHANNEL_PAGE&offset=$offset$q").text)
            ?: throw ErrorLoadingException("ntv.st returned no channel list")
        return ChannelPage(list.channels.filter { NTVStreamParser.isSupported(it) }, list.hasMore)
    }

    companion object {
        private const val TITAN = "titan"
        private const val CHANNEL_PAGE = 50
        private const val UPCOMING_LIMIT = 60
        private const val SEARCH_LIMIT = 40
        private const val MATCH_TTL_MS = 2 * 60_000L
    }
}
