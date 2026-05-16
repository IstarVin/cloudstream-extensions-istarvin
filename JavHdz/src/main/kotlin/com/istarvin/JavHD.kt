package com.istarvin

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.io.encoding.Base64

class JavHD : MainAPI() {
    override var mainUrl = "https://javhdz.men"
    override var name = "JavHD"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "category/censored-2" to "Censored",
        "category/uncensored-3" to "Uncensored",
        "category/beauty-4" to "Beauty and more",
    )

    private val hlsPngProxy = "https://hls-proxy.istarvin.uk"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document

        val home = document.select("#movie-last-movie > li").mapNotNull {
            it.mainPageResults()
        }

        return newHomePageResponse(
            data = request.copy(horizontalImages = true),
            list = home,
            hasNext = true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search/$query/page/$page"
        val document = app.get(url).document

        val results = document.select("#movie-last-movie > li").mapNotNull {
            it.mainPageResults()
        }

        return newSearchResponseList(results, results.isNotEmpty())
    }

    private fun Element.mainPageResults(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val title = link.attr("title").trim()
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img") ?: return null
        val poster = fixUrl(img.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private val getBase64LinkRegex = Regex("""window\.atob\("(.+?)"\)""")

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.header-title > a")?.text()?.trim() ?: return null
        val code =
            document.selectFirst("#film-content-wrapper")?.text()?.trim()?.substringBefore(" ")
        val poster = fixUrlNull(document.selectFirst("img.thumb")?.attr("src"))

        val videoUrlBase64 = document.selectFirst("#video > script:last-child")?.html()?.let {
            getBase64LinkRegex.find(it)?.groups[1]?.value
        } ?: return null

        val videoUrl = Base64.decode(videoUrlBase64).decodeToString()

        return newMovieLoadResponse(title, url, TvType.NSFW, "$code:$videoUrl") {
            this.posterUrl = poster
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.substringBefore(":").let { code ->
            getExtractorApiFromName("SubtitleCat").getUrl(
                url = code,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        val videoUrl = data.substringAfter(":")
        val urlEncoded = withContext(Dispatchers.IO) {
            URLEncoder.encode(
                videoUrl,
                "utf-8"
            )
        }

        generateM3u8(
            source = name,
            streamUrl = "$hlsPngProxy/proxy?referer=$mainUrl&url=$urlEncoded",
            referer = mainUrl
        ).forEach(callback)

        return true
    }
}