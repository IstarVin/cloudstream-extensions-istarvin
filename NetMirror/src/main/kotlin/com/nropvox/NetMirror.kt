package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/** NetMirror's own catalog and signed Artplayer pages; media uses its extension's referer. */
class NetMirror : MainAPI() {
    override var mainUrl = "https://netmirror.plus"
    override var name = "NetMirror"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val api = "https://api2.imdb3.shop/api"
    private val searchApi = "https://api2.imdb4.shop/api/search2"
    private val mediaReferer = "https://movieboxonline.net/"
    private val headers get() = mapOf("Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        "/tranding/list?id=60&items_per_page=21" to "Trending Movies",
        "/tranding/list?id=61&items_per_page=21" to "Trending Series",
        "/tranding/list?id=13&items_per_page=21" to "Hollywood",
        "/tranding/list?id=14&items_per_page=21" to "Bollywood",
        "/tranding/list?id=18&items_per_page=21" to "K-Drama",
        "/tranding/list?id=39&items_per_page=21" to "C-Drama",
        "/tranding/list?id=40&items_per_page=21" to "Western TV",
        "/tranding/list?id=30&items_per_page=21" to "Anime (English Dubbed)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = NetMirrorParser.catalog(app.get("$api${request.data}&page=${(page - 1).coerceAtLeast(0)}", headers = headers).text)
        return newHomePageResponse(
            HomePageList(request.name, result.items.mapNotNull { card(it) }, isHorizontalImages = false),
            hasNext = result.hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val result = NetMirrorParser.catalog(app.get(
            "$searchApi/${NetMirrorParser.encode(query.trim().lowercase())}?page=${(page - 1).coerceAtLeast(0)}", headers = headers
        ).text)
        return newSearchResponseList(result.items.mapNotNull { card(it) }, hasNext = result.hasNext)
    }

    private fun card(item: JsonNode): SearchResponse? {
        val title = org.jsoup.parser.Parser.unescapeEntities(NetMirrorParser.text(item, "title"), false)
            .takeIf { it.isNotBlank() } ?: return null
        val type = NetMirrorParser.text(item, "media_type")
        val id = NetMirrorParser.text(item, "id").takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return null
        // Match the site's exclusion of explicitly adult catalog entries.
        if (item.path("genre").any { it.asText() in setOf("101", "273") }) return null
        val poster = poster(item)
        val year = NetMirrorParser.year(NetMirrorParser.text(item, "release_date"))
        val rating = NetMirrorParser.text(item, "vote_average").toDoubleOrNull()
        return when (type) {
            "movie" -> newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                posterUrl = poster
                this.year = year
                score = Score.from10(rating)
            }
            "tv" -> newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                score = Score.from10(rating)
            }
            else -> null
        }
    }

    private fun poster(item: JsonNode): String? = NetMirrorParser.text(item, "backdrop_path")
        .takeIf { it.startsWith("https://") || it.startsWith("http://") }
        ?.let { if ('?' in it) it else "$it?x-oss-process=image/resize%2Cw_300" }

    private suspend fun details(ref: NetMirrorParser.TitleRef): JsonNode =
        NetMirrorParser.catalog(app.get("$api/${ref.type}/${ref.id}", headers = headers).text).items.firstOrNull()
            ?: throw ErrorLoadingException("NetMirror could not find this title")

    override suspend fun load(url: String): LoadResponse {
        val ref = NetMirrorParser.reference(url) ?: throw ErrorLoadingException("Invalid NetMirror title URL")
        val item = details(ref)
        val title = org.jsoup.parser.Parser.unescapeEntities(NetMirrorParser.text(item, "title"), false)
            .ifBlank { throw ErrorLoadingException("NetMirror returned a title without a name") }
        val response = if (ref.type == "movie") {
            newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/movie/${ref.id}")
        } else {
            val episodes = item.path("season").filter { it.path("se").asInt() >= 0 }.flatMap { season ->
                val number = season.path("se").asInt()
                NetMirrorParser.episodes(season).map { ep ->
                    newEpisode("$mainUrl/tv/${ref.id}?season=$number&episode=$ep") {
                        name = "Episode $ep"
                        this.season = number
                        episode = ep
                    }
                }
            }
            if (episodes.isEmpty()) throw ErrorLoadingException("NetMirror has no episodes for this series")
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)
        }
        return response.apply {
            posterUrl = poster(item)
            plot = org.jsoup.Jsoup.parse(NetMirrorParser.text(item, "dis")).text().trim('"')
            year = NetMirrorParser.year(NetMirrorParser.text(item, "release_date"))
            score = Score.from10(NetMirrorParser.text(item, "vote_average").toDoubleOrNull())
            duration = NetMirrorParser.text(item, "duration").toIntOrNull()?.takeIf { it > 0 }?.div(60)
            actors = item.path("stafflist").mapNotNull {
                val actor = NetMirrorParser.text(it, "name").takeIf(String::isNotBlank) ?: return@mapNotNull null
                ActorData(Actor(actor, NetMirrorParser.text(it, "avatarUrl").ifBlank { null }), roleString = NetMirrorParser.text(it, "character"))
            }
            tags = item.path("genre").mapNotNull { genres[it.asText()] }
            NetMirrorParser.text(item, "tm_id").takeIf(String::isNotBlank)?.let { addTMDbId(it) }
            NetMirrorParser.text(item, "trailer").takeIf(String::isNotBlank)?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = NetMirrorParser.reference(data) ?: return false
        if (ref.type == "tv" && ref.episode <= 0) return false
        // Fetch details and sign at play time: saved episodes never contain expired media URLs.
        val item = details(ref)
        val path = NetMirrorParser.playerPath(item, ref, System.currentTimeMillis() / 1000)
        for (host in listOf("https://webvp.watch21.shop", "https://bet.watch22.shop", "https://limit.watch22.shop")) {
            val player = try {
                NetMirrorParser.player(app.get("$host$path", headers = headers, timeout = 25).text)
            } catch (e: Exception) {
                Log.w(name, "Player ${host}: ${e.message}")
                continue
            }
            if (player.videos.isEmpty()) continue
            player.subtitles.forEach { subtitleCallback(newSubtitleFile(it.label, it.url)) }
            player.videos.forEach { video ->
                callback(newExtractorLink(name, "$name ${video.label}", video.url,
                    if (video.url.substringBefore('?').endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    quality = video.quality.takeIf { it > 0 } ?: Qualities.Unknown.value
                    referer = if (java.net.URI(video.url).host == "bcdnt.hakunaymatata.com") "$host/" else mediaReferer
                })
            }
            return true
        }
        return false
    }

    private val genres = mapOf(
        "200" to "Action", "8" to "Adventure", "23" to "Thriller", "10" to "Animation",
        "20" to "Romance", "11" to "Short", "12" to "Comedy", "13" to "Crime", "14" to "Drama",
        "15" to "History", "16" to "Mystery", "25" to "Documentary", "27" to "Sport", "30" to "Sci-Fi",
        "32" to "War", "41" to "Fantasy", "34" to "Horror", "29" to "Biography", "38" to "Family",
        "43" to "Reality-TV", "47" to "Music", "58" to "Western"
    )
}
