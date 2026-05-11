package com.istarvin

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Reelxia : MainAPI() {
    override var mainUrl = "https://reelxia.com"
    override var name = "Reelxia"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val apiUrl = "https://api.reelxia.com"
    private val imageUrl = "https://img.reely.live"

    private val pageLimit = 20

    override val mainPage = mainPageOf(
        "new" to "New",
        "popular" to "Popular",
        "updated" to "Updated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$apiUrl/books/browse?category=${request.data}&page=$page&limit=$pageLimit"
        val booksResponse = app.get(url).parsedSafe<BooksResponse>() ?: return null

        val home = booksResponse.books
            .map { it.toMainPageResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = page < booksResponse.totalPages)
    }

    fun Book.toMainPageResult(): SearchResponse = newTvSeriesSearchResponse(
        name = this.bookName,
        url = this.shortId,
        type = TvType.TvSeries
    ) {
        posterUrl = "$imageUrl/${this@toMainPageResult.coverImageUrl}"
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$apiUrl/books/search?q=$query&page=$page&limit=$pageLimit"
        val booksResponse = app.get(url).parsedSafe<BooksResponse>() ?: return null

        val searchResult = booksResponse.books
            .map { it.toMainPageResult() }
            .distinctBy { it.url }

        return newSearchResponseList(searchResult, hasNext = page < booksResponse.totalPages)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("/")
        val url = "$apiUrl/books/$id"
        val booksDetail = app.get(url).parsedSafe<BookDetail>() ?: return null

        val episodes = booksDetail.videoPlayerSources.flatMap { source ->
            source.parts.mapIndexed { i, part ->
                newEpisode(part.embedUrl) {
                    name = source.platform
                    if (source.parts.size > 1) {
                        name += " Part ${i + 1}"
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(
            name = booksDetail.bookName,
            url = "$mainUrl/drama/$id",
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            posterUrl = "$imageUrl/${booksDetail.coverImageUrl}"
            tags = booksDetail.tags
            plot = booksDetail.description
            score = Score.from5(booksDetail.averageRating)
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

    data class BooksResponse(
        val books: List<Book>,
        val currentPage: Int,
        val totalPages: Int,
        val totalItems: Int
    )

    data class Book(
        val shortId: String,
        val bookName: String,
        val coverImageUrl: String,
        val tags: List<String>,
    )

    data class BookDetail(
        val alternativeTitles: List<String>?,
        val averageRating: Double,
        val bookName: String,
        val coverImageUrl: String,
        val createdAt: String,
        val description: String,
        val expectedChapterCount: Int,
        val isFullyProcessed: Boolean,
        val shortId: String,
        val tags: List<String>,
        val totalRatings: Int,
        val totalViews: Int,
        val updatedAt: String,
        val videoPlayerSources: List<VideoPlayerSource>
    )

    data class VideoPlayerSource(
        val overallStatus: String,
        val parts: List<VideoPart>,
        val platform: String
    )

    data class VideoPart(
        val embedUrl: String,
        val partNumber: Int?,
        val platform: String?,
        val playerUrl: String,
        val status: String,
        val success: Boolean
    )
}