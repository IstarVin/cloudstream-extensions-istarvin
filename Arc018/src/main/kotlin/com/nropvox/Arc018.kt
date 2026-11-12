package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

/** Server-rendered catalog with per-page AJAX player tokens; no account or challenge required. */
class Arc018 : MainAPI() {
    override var mainUrl = "https://arc018.stream"
    override var name = "ARC018"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "watch-movies-online" to "Latest Movies",
        "watch-series-online" to "Latest Series",
        "top-imdb" to "Top IMDb",
    )

    private fun Arc018Card.toSearchResponse(): SearchResponse =
        newTvSeriesSearchResponse(title, url, if (series) TvType.TvSeries else TvType.Movie) {
            posterUrl = poster
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}/" + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document
        return newHomePageResponse(
            HomePageList(request.name, Arc018Parser.cards(document).map { it.toSearchResponse() }, false),
            hasNext = Arc018Parser.hasNext(document, page),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val document = app.get("$mainUrl/search?keyword=${URLEncoder.encode(query, "UTF-8")}&page=$page").document
        return newSearchResponseList(
            Arc018Parser.cards(document).map { it.toSearchResponse() }, Arc018Parser.hasNext(document, page),
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val details = Arc018Parser.details(document) ?: throw ErrorLoadingException("ARC018 title details are missing")
        val response = if (details.series) {
            if (details.episodes.isEmpty()) throw ErrorLoadingException("ARC018 has no episodes for this series")
            newTvSeriesLoadResponse(details.title, url, TvType.TvSeries, details.episodes.map {
                newEpisode(it.url) { season = it.season; episode = it.episode }
            })
        } else newMovieLoadResponse(details.title, url, TvType.Movie, url)
        return response.apply {
            posterUrl = details.poster
            backgroundPosterUrl = details.backdrop
            plot = details.plot
            year = details.year
            tags = details.genres
            duration = details.duration
            actors = details.actors.map { ActorData(Actor(it)) }
            recommendations = Arc018Parser.cards(document).map { it.toSearchResponse() }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        val uri = runCatching { URI(data) }.getOrNull() ?: return false
        if (uri.host != URI(mainUrl).host) return false
        val document = app.get(data).document
        val token = Arc018Parser.playerToken(document) ?: return false
        val parameter = if (uri.path.startsWith("/episode/")) "players_show" else "players"
        val servers = Arc018Parser.servers(app.post("$mainUrl/ajax/ajax.php", data = mapOf(parameter to token), referer = data).text)
        val emitted = mutableSetOf<String>()
        val subtitles = mutableSetOf<String>()
        for (server in servers.sortedBy { if (it.name.equals("Vidmoly", true)) 0 else 1 }) {
            try {
                try {
                    Arc018Parser.subtitleEndpoint(server.link)?.let { endpoint ->
                        if (subtitles.add(endpoint)) {
                            Arc018Parser.tracks(app.get(endpoint, referer = "$mainUrl/").text).forEach {
                                subtitleCallback(newSubtitleFile(it.label, it.url))
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w(name, "Subtitles: ${error.message}")
                }
                val host = URI(server.link).host.orEmpty()
                if (host == "kaembed.net" || host.contains("vidmoly")) {
                    val referer = "${URI(server.link).scheme}://$host/"
                    val html = app.get(server.link, referer = "$mainUrl/").text
                    for (stream in Arc018Parser.vidmolyStreams(html)) {
                        emitHls("$name Vidmoly", stream, referer, emitted, callback)
                    }
                } else if (host == "mfw09.org") {
                    for (stream in Arc018Filemoon.resolve(server.link, "$mainUrl/")) {
                        emitHls("$name Filemoon", stream, "https://$host/", emitted, callback)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(name, "${server.name}: ${error.message}")
            }
        }
        return emitted.isNotEmpty()
    }

    private suspend fun emitHls(
        label: String, url: String, referer: String, emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit,
    ) {
        if (!emitted.add(url)) return
        val links = try {
            generateM3u8(label, url, referer)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(name, "Playlist: ${error.message}")
            emptyList()
        }
        if (links.isEmpty()) {
            callback(newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
                this.referer = referer
                quality = Qualities.Unknown.value
            })
        } else links.forEach(callback)
    }

}
