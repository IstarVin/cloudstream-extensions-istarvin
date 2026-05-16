package com.istarvin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MkvDrama : MainAPI() {
    override var mainUrl = "https://mkvdrama.org"
    override var name = "MkvDrama"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val imageUrl = "https://i2.wp.com/mkvdrama.org"

    private val pageLimit = 20

    override val mainPage = mainPageOf(
        "titles?order=latest" to "Latest",
        "titles?order=popular" to "Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&per_page=$pageLimit&page=$page"
        val document = app.get(
            url,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        ).document

        val home = document.select("#content article")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(".page-next") != null
        )
    }

    fun Element.toMainPageResult(): SearchResponse? {
        val anchorTag = this.selectFirst("a") ?: return null

        return newTvSeriesSearchResponse(
            name = anchorTag.attr("title"),
            url = fixUrl(anchorTag.attr("href")),
            type = TvType.TvSeries
        ) {
            posterUrl = this@toMainPageResult.selectFirst("img")?.attr("src")
        }
    }


    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search?q=$query&per_page=$pageLimit&page=$page"
        val document = app.get(
            url,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        ).document

        val searchResult = document.select("#content article")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newSearchResponseList(
            searchResult,
            hasNext = document.selectFirst(".page-next") != null
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val series = app.get(url).parsedSafe<SeriesResponse>()?.series ?: return null

        val episodes = series.totalEpisodes.let { totalEpisodes ->
            (1..totalEpisodes).map {
                newEpisode(it) {
                    name = "Episode $it"
                }
            }
        }

        return newTvSeriesLoadResponse(
            name = series.title,
            url = url,
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            posterUrl = "$imageUrl/${series.coverUrl}"
            tags = series.tags.mapNotNull { it.label }
            plot = series.synopsis
            score = Score.from10(series.score)
            year = series.aired.substringBefore("-").trim().substringAfterLast(" ").toIntOrNull()

            addActors(series.cast.map { it.name })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(data, subtitleCallback, callback)
        return true
    }

    data class SeriesResponse(
        @JsonProperty("series")
        val series: Drama,

        @JsonProperty("canonical")
        val canonical: String,
    )

    // Main Drama class
    data class Drama(
        @JsonProperty("id")
        val id: Int,

        @JsonProperty("title")
        val title: String,

        @JsonProperty("slug")
        val slug: String,

        @JsonProperty("type")
        val type: String,

        @JsonProperty("status")
        val status: String,

        @JsonProperty("release_date")
        val releaseDate: String,

        @JsonProperty("duration_minutes")
        val durationMinutes: Int,

        @JsonProperty("score")
        val score: Double,

        @JsonProperty("synopsis")
        val synopsis: String,

        @JsonProperty("total_episodes")
        val totalEpisodes: Int,

        @JsonProperty("trailer_id")
        val trailerId: String?,

        @JsonProperty("cover_url")
        val coverUrl: String,

        @JsonProperty("big_cover_url")
        val bigCoverUrl: String?,

        @JsonProperty("gallery")
        val gallery: String?,

        @JsonProperty("aired_days")
        val airedDays: List<String>,

        @JsonProperty("is_subbed")
        val isSubbed: Boolean,

        @JsonProperty("is_dubbed")
        val isDubbed: Boolean,

        @JsonProperty("is_censored")
        val isCensored: Boolean,

        @JsonProperty("is_mature_content")
        val isMatureContent: Boolean,

        @JsonProperty("is_hot")
        val isHot: Boolean,

        @JsonProperty("alternative_title")
        val alternativeTitle: String,

        @JsonProperty("sub_type")
        val subType: String,

        @JsonProperty("maturity_rating")
        val maturityRating: String?,

        @JsonProperty("meta_title")
        val metaTitle: String?,

        @JsonProperty("meta_description")
        val metaDescription: String?,

        @JsonProperty("aired")
        val aired: String,

        @JsonProperty("post_status")
        val postStatus: String,

        @JsonProperty("visibility")
        val visibility: String,

        @JsonProperty("show_title_on_frontend")
        val showTitleOnFrontend: Boolean,

        @JsonProperty("comments_enabled")
        val commentsEnabled: Boolean,

        @JsonProperty("genres")
        val genres: List<Genre>,

        @JsonProperty("studios")
        val studios: List<Studio>,

        @JsonProperty("seasons")
        val seasons: List<Season>,

        @JsonProperty("directors")
        val directors: List<Person>,

        @JsonProperty("cast")
        val cast: List<Person>,

        @JsonProperty("networks")
        val networks: List<Network>,

        @JsonProperty("countries")
        val countries: List<Country>,

        @JsonProperty("tags")
        val tags: List<Tag>,

        @JsonProperty("published_at")
        val publishedAt: String
    )

    // Genre class
    data class Genre(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("slug")
        val slug: String,

        @JsonProperty("id")
        val id: Int,

        @JsonProperty("label")
        val label: String?
    )

    // Studio class
    data class Studio(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("slug")
        val slug: String,

        @JsonProperty("id")
        val id: Int,

        @JsonProperty("label")
        val label: String?
    )

    // Season class
    data class Season(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("slug")
        val slug: String,

        @JsonProperty("id")
        val id: Int,

        @JsonProperty("label")
        val label: String?
    )

    // Person class (for directors and cast)
    data class Person(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("slug")
        val slug: String,

        @JsonProperty("id")
        val id: Int,

        @JsonProperty("label")
        val label: String?
    )

    // Network class
    data class Network(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("slug")
        val slug: String,

        @JsonProperty("id")
        val id: Int,

        @JsonProperty("label")
        val label: String?
    )

    // Country class
    data class Country(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("slug")
        val slug: String,

        @JsonProperty("id")
        val id: Int,

        @JsonProperty("label")
        val label: String?
    )

    // Tag class
    data class Tag(
        @JsonProperty("name")
        val name: String,

        @JsonProperty("slug")
        val slug: String,

        @JsonProperty("id")
        val id: Int,

        @JsonProperty("label")
        val label: String?
    )
}