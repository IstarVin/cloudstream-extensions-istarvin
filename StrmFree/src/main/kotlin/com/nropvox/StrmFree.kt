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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * strmfree.pk (StreamFree) is a client-rendered site over a public JSON API (`/api/v1/streams`, `/api/v1/sources/{key}`).
 * Each source is an embed page that carries a signed playlist URL (`_t`/`_e`/`_n`, valid for hours) in its script;
 * the playlist is plain HLS whose segments sit on a CDN behind a `.png` disguise that ExoPlayer's TS sniffer
 * skips (the TS packets start 64 bytes in). See `StrmFreeParser.embed`.
 */
class StrmFree : MainAPI() {
    override var mainUrl = "https://strmfree.pk"
    override var name = "StrmFree"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val listLock = Mutex()
    @Volatile private var listed: Pair<List<SfStream>, Long>? = null

    override val mainPage = mainPageOf(
        "live" to "Live Now",
        "upcoming" to "Upcoming",
        *CATEGORIES.map { "cat:$it" to StrmFreeParser.categoryTitle(it) }.toTypedArray(),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = streams()
        val items = when {
            request.data == "live" -> StrmFreeParser.live(all)
            request.data == "upcoming" -> StrmFreeParser.upcoming(all, System.currentTimeMillis() / 1000).take(UPCOMING_LIMIT)
            request.data.startsWith("cat:") -> StrmFreeParser.inCategory(all, request.data.removePrefix("cat:"))
            else -> emptyList()
        }
        // Everything is one request, so only the first page has anything.
        return newHomePageResponse(
            HomePageList(request.name, if (page > 1) emptyList() else items.map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = false
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty() || page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val hits = StrmFreeParser.ordered(streams().filter { StrmFreeParser.matches(it, q) }).take(SEARCH_LIMIT)
        return newSearchResponseList(hits.map { it.toSearchResponse() }, hasNext = false)
    }

    private fun SfStream.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(name.orEmpty(), StrmFreeParser.playerUrl(mainUrl, category.orEmpty(), key.orEmpty()), TvType.Live) {
            posterUrl = StrmFreeParser.thumbnail(mainUrl, this@toSearchResponse)
        }

    override suspend fun load(url: String): LoadResponse {
        val (category, key) = StrmFreeParser.playerRef(url) ?: throw ErrorLoadingException("Not a $name player URL: $url")
        val stream = streams().firstOrNull { it.key == key }
            ?: StrmFreeParser.stream(app.get("$mainUrl/api/v1/streams/${java.net.URLEncoder.encode(key, "UTF-8")}").text)
            ?: throw ErrorLoadingException("$key is no longer listed on $name (events disappear once they end)")
        val playerUrl = StrmFreeParser.playerUrl(mainUrl, stream.category ?: category, key)
        return newLiveStreamLoadResponse(stream.name.orEmpty(), playerUrl, playerUrl) {
            posterUrl = StrmFreeParser.thumbnail(mainUrl, stream)
            plot = StrmFreeParser.plot(stream, System.currentTimeMillis() / 1000)
            tags = listOfNotNull(
                stream.category?.let(StrmFreeParser::categoryTitle),
                stream.league?.trim()?.ifEmpty { null },
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (category, key) = StrmFreeParser.playerRef(data) ?: return false
        val sources = StrmFreeParser.sources(app.get("$mainUrl/api/v1/sources/${java.net.URLEncoder.encode(key, "UTF-8")}").text)
            .filter { it.live }
        if (sources.isEmpty()) {
            Log.w(name, "$key has no live source yet")
            return false
        }
        val found = CopyOnWriteArrayList<Boolean>()
        runAllAsync(*sources.mapIndexed { index, source ->
            suspend {
                val resolved = try {
                    resolve(category, source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "${source.url}: ${e.message}")
                    null
                }
                if (resolved != null) {
                    callback(newExtractorLink(name, StrmFreeParser.sourceLabel(name, source, index), resolved.url, ExtractorLinkType.M3U8) {
                        quality = resolved.height
                    })
                    found.add(true)
                }
            }
        }.toTypedArray())
        return found.isNotEmpty()
    }

    private class Resolved(val url: String, val height: Int)

    /**
     * The signed playlist URL of one source. The site's own player builds it from `/get-stream-key` and the embed's
     * `_0x` map; which CDN path answers varies, so each is tried and the first real playlist wins. When none
     * answers the predicted URL is still returned, because the check can fail where the player would not.
     */
    private suspend fun resolve(category: String, source: SfSource): Resolved? {
        val variant = StrmFreeParser.variantOf(source.url.orEmpty())
        val embedUrl = StrmFreeParser.embedUrl(mainUrl, category, variant)
        val embed = StrmFreeParser.embed(app.get(embedUrl, referer = "$mainUrl/").text)
            ?: throw ErrorLoadingException("no player data in $embedUrl")
        val height = StrmFreeParser.heightOfQuality(embed.quality).takeIf { it > 0 } ?: StrmFreeParser.heightOfLabel(source.quality)
        val keyInfo = StrmFreeParser.streamKey(app.get("$mainUrl/get-stream-key/${embed.key}", referer = embedUrl).text)
        if (keyInfo?.isExternal == true && !keyInfo.externalUrl.isNullOrBlank()) return Resolved(keyInfo.externalUrl, height)

        val candidates = StrmFreeParser.playlistPrefixes(keyInfo?.serverName).mapNotNull { StrmFreeParser.playlistUrl(mainUrl, it, embed) }
        if (candidates.isEmpty()) throw ErrorLoadingException("$embedUrl is not live (no playlist signature)")
        repeat(2) {
            for (candidate in candidates) {
                val ok = runCatching { StrmFreeParser.isPlaylist(app.get(candidate, referer = embedUrl).text) }.getOrDefault(false)
                if (ok) return Resolved(candidate, height)
            }
        }
        Log.w(name, "$variant: no playlist path answered, returning ${candidates.first().substringBefore('?')}")
        return Resolved(candidates.first(), height)
    }

    /** The listed streams, kept for [LIST_TTL_MS] (the site caches the list for about 20 s too). */
    private suspend fun streams(): List<SfStream> {
        listed?.takeIf { System.currentTimeMillis() - it.second < LIST_TTL_MS }?.let { return it.first }
        return listLock.withLock {
            listed?.takeIf { System.currentTimeMillis() - it.second < LIST_TTL_MS }?.let { return@withLock it.first }
            val fresh = StrmFreeParser.streams(app.get("$mainUrl/api/v1/streams").text)
            if (fresh.isEmpty()) listed?.first ?: throw ErrorLoadingException("$name returned no streams")
            else fresh.also { listed = it to System.currentTimeMillis() }
        }
    }

    companion object {
        /** `GET /api/v1/categories`, in the site's order. */
        private val CATEGORIES = listOf("soccer", "basketball", "football", "hockey", "baseball", "combat", "racing", "tennis", "cricket")
        private const val UPCOMING_LIMIT = 60
        private const val SEARCH_LIMIT = 50
        private const val LIST_TTL_MS = 30_000L
    }
}
