package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * WordPress (DooPlay) download site behind a Cloudflare challenge; each post lists file-host links per episode. Its
 * requests and link resolving are in `shared/onlykdramasource`, shared with OmniStream.
 */
class OnlyKDrama : MainAPI() {
    override var mainUrl = OnlyKDramaSource.SITE
    override var name = "OnlyKDrama"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "drama/" to "Latest Dramas",
        "genres/ongoing/" to "Ongoing",
        "genres/koren-drama/" to "Korean Dramas",
        "genres/chinese-drama/" to "Chinese Dramas",
        "genres/hindi-dubbed-drama/" to "Hindi Dubbed Dramas",
        "genres/english-dubbed-drama/" to "English Dubbed Dramas",
        "movies/" to "Movies",
        "genres/hindi-dubbed-movie/" to "Hindi Dubbed Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        val response = OnlyKDramaSource.get(url)
        val doc = response.document
        val cards = OnlyKDramaParser.cards(doc, mainUrl)
        com.lagradost.api.Log.i(name, "TRACE home $url -> ${response.code} ${response.text.length} chars, ${cards.size} cards, title=${doc.title()}")
        return newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, hasNext = OnlyKDramaParser.hasNext(doc))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val doc = OnlyKDramaSource.search(query, page) ?: return newSearchResponseList(emptyList(), hasNext = false)
        val cards = OnlyKDramaParser.cards(doc, mainUrl)
        return newSearchResponseList(cards.map { it.toSearchResponse() }, hasNext = cards.isNotEmpty() && OnlyKDramaParser.hasNext(doc))
    }

    private fun OkCard.toSearchResponse(): SearchResponse = if (movie) {
        newMovieSearchResponse(title, url, TvType.Movie) {
            posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    } else {
        newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
            posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = OnlyKDramaSource.get(url).document
        val title = OnlyKDramaParser.title(doc) ?: throw ErrorLoadingException("No title on $url")
        val entries = OnlyKDramaParser.entries(doc)

        val load: LoadResponse = if (OnlyKDramaParser.isMovie(url)) {
            if (entries.isEmpty()) throw ErrorLoadingException("No playable download links on $url")
            newMovieLoadResponse(title, url, TvType.Movie, OkLinks(entries.map { it.file }).toJson())
        } else {
            // One episode is listed once per release and host; every link of it is resolved together.
            val episodes = entries.filter { it.episode != null }
                .groupBy { (it.season ?: 1) to it.episode!! }
                .toSortedMap(compareBy({ it.first }, { it.second }))
                .map { (key, links) ->
                    newEpisode(OkLinks(links.map { it.file }.distinctBy { it.url }).toJson()) {
                        season = key.first
                        episode = key.second
                        name = "Episode ${key.second}"
                    }
                }
            if (episodes.isEmpty()) throw ErrorLoadingException("No playable episode links on $url")
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes).apply {
                showStatus = when (OnlyKDramaParser.ongoing(doc)) {
                    true -> ShowStatus.Ongoing
                    false -> ShowStatus.Completed
                    null -> null
                }
            }
        }
        return load.apply {
            posterUrl = OnlyKDramaParser.poster(doc)
            plot = OnlyKDramaParser.plot(doc)
            year = OnlyKDramaParser.year(doc)
            tags = OnlyKDramaParser.genres(doc)
            score = OnlyKDramaParser.rating(doc)?.let { Score.from10(it) }
            addTrailer(OnlyKDramaParser.trailer(doc))
            addTMDbId(OnlyKDramaParser.tmdbId(doc)?.toString())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = tryParseJson<OkLinks>(data) ?: return false
        return OnlyKDramaSource.loadLinks(links, name, callback)
    }
}
