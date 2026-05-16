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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder

class JavFC2 : MainAPI() {
    override var mainUrl = "https://javfc2.xyz"
    override var name = "JavFC2"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "home/vids" to "Latest",
        "genre/eng-sub" to "English Subtitle",
        "genre/fc2" to "FC2PPV",
        "genre/jav" to "JAV",
        "genre/webcam" to "WebCam",
        "home/ranking?year=2026&month=all" to "Top 2026",
        "home/ranking?year=2025&month=all" to "Top 2025",
        "home/ranking?year=2024&month=all" to "Top 2024",
        "home/ranking?year=2023&month=all" to "Top 2023",
        "home/ranking?year=2022&month=all" to "Top 2022",
    )

    private val hlsPngProxy = "https://hls-proxy.istarvin.uk"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var trueData = request.data

        var truePage = page
        if ("genre" in request.data) {
            truePage = 24 * (page - 1)
        }

        if ("ranking" in request.data) {
            val params = request.data.substringAfter("?")
            trueData = "home/ranking/$page?$params"
        } else {
            trueData = "$trueData/$truePage"
        }

        val url = "$mainUrl/$trueData"
        val document = app.get(url).document

        val home = document.select(".movie-container > div").mapNotNull {
            it.mainPageResults()
        }

        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search/?q=$query&per_page=${(page - 1) * 24}"
        println(url)
        val document = app.get(url).document

        val results = document.select(".movie-container > div").mapNotNull {
            it.mainPageResults()
        }

        return newSearchResponseList(results, results.isNotEmpty())
    }

    private fun Element.mainPageResults(): SearchResponse? {
        if (selectFirst(".label")?.text()?.trim() in setOf("Actor", "Seller")) {
            return null
        }

        val link = this.selectFirst(".movie-title a") ?: return null
        val title = link.text().trim()
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img") ?: return null
        val poster = fixUrl(img.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private val srcRegex = Regex("""src:\s*['"](https?://[^'"]+[^'"]*)['"]""")

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".title")?.text()?.trim() ?: return null
        val code = url.substringAfterLast("/").substringBefore(".html")
        val poster = fixUrlNull(document.selectFirst("#info img")?.attr("src"))

        val videoUrl = document.select("#player-div script").html().let {
            srcRegex.find(it)?.groups[1]?.value
        } ?: return null

        val subtitleUrl = document.selectFirst("track[kind=\"subtitles\"]")?.attr("src")

        val data = "$code:$videoUrl**$subtitleUrl"

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            data = data
        ) {
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

        val subtitleUrl = data.substringAfter("**")

        subtitleCallback(newSubtitleFile("En", subtitleUrl))

        val videoUrl = data.substringAfter(":").substringBefore("**")
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