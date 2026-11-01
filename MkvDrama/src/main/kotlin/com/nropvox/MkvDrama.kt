package com.nropvox

import android.content.SharedPreferences
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import java.net.URLEncoder

const val PIXELDRAIN_API_KEY_PREF_KEY = "pixeldrain_api_key"

class MkvDrama(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {
    override var mainUrl = "https://mkvdrama.net"
    override var name = "MkvDrama"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override val mainPage = mainPageOf(
        "type=drama&order=latest" to "Latest dramas",
        "type=mini_drama&order=latest" to "Mini dramas",
        "type=movie&order=latest" to "Movies",
        "order=popular" to "Popular",
        "order=rating" to "Top rated",
    )
    private val linkCache = MkvDramaCache()

    private fun image(value: String?): String? = value?.let { URI(mainUrl).resolve(it).toString() }
        ?.takeIf(MkvDramaDecoder::isHttp)?.let { url ->
            if (URI(url).host == URI(mainUrl).host && URI(url).path.startsWith("/wp-content/"))
                "https://i2.wp.com/${url.substringAfter("://")}" else url
        }

    private fun MkvTitleCard.toCard(): SearchResponse =
        if (movie) newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = image(poster) }
        else newTvSeriesSearchResponse(title, url, TvType.AsianDrama) { posterUrl = image(poster) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = MkvDramaSession().get("$mainUrl/titles/?${request.data}&page=$page")
        val result = MkvDramaParser.catalog(response.text, response.url)
        return newHomePageResponse(request.name, result.items.map { it.toCard() }, hasNext = result.hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val url = "$mainUrl/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$page"
        val response = MkvDramaSession().get(url)
        val result = MkvDramaParser.catalog(response.text, response.url)
        return newSearchResponseList(result.items.map { it.toCard() }, hasNext = result.hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val session = MkvDramaSession()
        val canonical = URI(mainUrl).resolve(URI(url).path).toString()
        val page = session.get(canonical)
        val details = MkvDramaParser.details(page.text, page.url)
        if (details.movie) {
            return newMovieLoadResponse(details.title, canonical, TvType.Movie, mkvJson.writeValueAsString(MkvEpisodeData(canonical))) {
                posterUrl = image(details.poster)
                year = details.year
                plot = details.plot
                tags = details.genres
                score = Score.from10(details.rating)
                duration = details.duration
                addActors(details.actors)
            }
        }
        val releases = MkvDramaDecoder.releases(session.panel(page), canonical)
        val regular = releases.filterNot { MkvDramaDecoder.isExtra(it.group) }
        // The metadata total is a planned count; expose only ranges published in the panel.
        val numbers = regular.flatMap { item ->
            MkvDramaDecoder.span(item.group)?.let { range ->
                if (range.last <= 2000) (range.first..range.last).toList() else emptyList()
            }.orEmpty()
        }.distinct().sorted()
        val episodes = numbers.map { number ->
            newEpisode(mkvJson.writeValueAsString(MkvEpisodeData(canonical, number))) {
                name = "Episode $number"
                episode = number
            }
        } + releases.filter { MkvDramaDecoder.isExtra(it.group) }.distinctBy { it.group }.map { item ->
            newEpisode(mkvJson.writeValueAsString(MkvEpisodeData(canonical, group = item.group))) { name = item.group }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("MKVDrama has no published, identifiable episode links for this title")
        return newTvSeriesLoadResponse(details.title, canonical, TvType.AsianDrama, episodes) {
            posterUrl = image(details.poster)
            year = details.year
            plot = details.plot
            tags = details.genres
            score = Score.from10(details.rating)
            duration = details.duration
            showStatus = when (details.status) {
                "completed" -> ShowStatus.Completed
                "ongoing" -> ShowStatus.Ongoing
                else -> null
            }
            addActors(details.actors)
        }
    }

    private fun pixeldrainApiKey(): String =
        sharedPref?.getString(PIXELDRAIN_API_KEY_PREF_KEY, "")?.trim().orEmpty()

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val target = mkvJson.readValue(data, MkvEpisodeData::class.java)
        if (!MkvDramaLinks(linkCache, target, pixeldrainApiKey(), subtitleCallback, callback).play())
            throw ErrorLoadingException("No playable MKVDrama mirror resolved. Shortener/Filecrypt verification or an unsupported file host may block this release.")
        return true
    }
}
