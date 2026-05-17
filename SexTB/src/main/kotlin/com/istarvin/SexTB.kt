package com.istarvin

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Element

class SexTB : MainAPI() {
    override var mainUrl = "https://sextb.net"
    override var name = "SexTB"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiKey =
        "Y1ZGUWNVSnROVlJOTUVWMlZsUldVMjlsWjBGelFUMDk6T0ZaNksxQmhjVTFhTHpCdFlWZDFNbE5CUm01Qlp6MDk="

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/jav-uncensored?genre=all&studio=all&quality=all&year=all&sort=viewed" to "Uncensored",
        "${mainUrl}/genre/beautiful-girl" to "Beautiful Girl",
        "${mainUrl}/genre/beautiful-pussy" to "Beautiful Pussy",
        "${mainUrl}/studio/fc2ppv" to "FC2PPV",
        "${mainUrl}/jav-subtitle" to "Subtitle"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.removeSuffix("/")}/pg-$page"
        }

        val document = app.get(url, headers = commonHeaders).document
        val items = document.select("div.tray-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)), hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) {
            "$mainUrl/search/$query"
        } else {
            "$mainUrl/search/$query/pg-$page"
        }

        val document = app.get(url, headers = commonHeaders).document
        val items = document.select("div.tray-item").mapNotNull {
            it.toSearchResult()
        }

        if (items.isEmpty()) {
            Log.d("STB_Search", "Search list returned empty! URL: $url")
        }

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href")

        if (href.startsWith("/search") || href.contains("javascript") || href.startsWith("/genre")) return null

        val fullHref = fixUrl(href)
        val title =
            this.selectFirst("div.tray-item-title")?.text()?.trim() ?: this.selectFirst("img")
                ?.attr("alt")?.trim() ?: return null

        val posterUrl =
            fixUrlNull(this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, fullHref, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = commonHeaders)
        val document = res.document

        val title = document.selectFirst("h1.film-info-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("#infomation img")?.attr("data-src"))

        val description = document.selectFirst("span.full-text-desc")?.text()?.trim()
        val yearText = document.selectFirst("div.description:has(i.fa-calendar) strong")?.text()
        val year =
            yearText?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val duration = document.selectFirst("div.description:has(i.fa-clock) strong")?.text()
            ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = document.select("div.description:has(i.fa-list) a").map { it.text() }
        val actors = document.select("div.description:has(i.fa-users) a").map { Actor(it.text()) }

        val recommendations = mutableListOf<SearchResponse>()
        val filmId = Regex("""var filmId\s*=\s*(\d+)""").find(res.text)?.groupValues?.get(1)

        if (filmId != null) {
            try {
                val apiRes =
                    app.get("${mainUrl}/ajax/related/$filmId", headers = commonHeaders).text
                val apiDoc = org.jsoup.Jsoup.parse(apiRes)
                apiDoc.select(".tray-item").forEach { el ->
                    val recName =
                        el.selectFirst(".tray-item-title")?.text()?.trim() ?: return@forEach
                    val recHref = fixUrl(el.selectFirst("a")?.attr("href") ?: return@forEach)
                    val recPoster = fixUrlNull(
                        el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")
                            ?.attr("src")
                    )
                    recommendations.add(
                        newMovieSearchResponse(
                            recName, recHref, TvType.NSFW
                        ) { this.posterUrl = recPoster })
                }
            } catch (_: Exception) {
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, data)
        val res = app.get(data, headers = commonHeaders)

        val tasks = mutableListOf<suspend () -> Unit>()

        tasks.add(
            suspend {
                res.document.select(".film-info-title a").attr("href")
                    .substringAfterLast("/")
                    .let { code ->
                        getExtractorApiFromName("SubtitleCat").run {
                            if (name == "SubtitleCat") {
                                getUrl(
                                    url = code,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback
                                )
                            }
                        }
                    }
            }
        )

        res.document.select(".episode-list button.btn-player").forEach { ep ->
            tasks.add(suspend suspend@{
                val res = app.get(data, headers = commonHeaders)

                val filmId = Regex("""var filmId\s*=\s*(\d+)""").find(res.text)?.groupValues?.get(1)
                val currentPt =
                    Regex("""__pt\s*=\s*['"](.*?)['"]""").find(res.text)?.groupValues?.get(1)
                val currentPk =
                    Regex("""__pk\s*=\s*['"](.*?)['"]""").find(res.text)?.groupValues?.get(1)

                if (filmId == null || currentPt == null) {
                    return@suspend
                }

                val episodeId = ep.attr("data-id")
                val sourceId = ep.attr("data-source").ifEmpty { filmId }
                Log.d(name, episodeId)

                try {
                    val postData = mapOf(
                        "episode" to episodeId, "filmId" to sourceId, "pt" to currentPt
                    )

                    val ajaxResponse = app.post(
                        "${mainUrl}/ajax/player", headers = mapOf(
                            "Referer" to data,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Authorization" to "Basic $apiKey",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "Accept" to "*/*",
                            "Origin" to mainUrl
                        ), data = postData
                    )

                    Log.d(name, "${ep.text().trim()} ${ajaxResponse.text}")

                    val responseData = ajaxResponse.parsedSafe<PlayerResponse>()
                    val encryptedPlayer = responseData?.playerEnc
                    val key = currentPk ?: ""

                    if (encryptedPlayer != null && key.isNotEmpty()) {
                        val decryptedRaw = decryptPlayer(encryptedPlayer, key)
                        Log.d(name, decryptedRaw)

                        val iframeUrl =
                            Regex("""src=\\?["'](https:.*?)(?:\?|\\?["']|["'])""").find(decryptedRaw)?.groupValues?.get(
                                1
                            )?.replace("\\/", "/")

                        if (iframeUrl != null && !iframeUrl.contains("upgrade")) {
                            loadExtractor(iframeUrl, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(name, "${e.message}")
                }
            })
        }

        runLimitedAsync(tasks = tasks.toTypedArray())

        return true
    }

    private fun decryptPlayer(encoded: String, key: String): String {
        return try {
            val decoded = base64Decode(encoded)
            val result = StringBuilder()
            for (i in decoded.indices) {
                result.append((decoded[i].code xor key[i % key.length].code).toChar())
            }
            result.toString()
        } catch (e: Exception) {
            Log.d(name, "${e.message}")
            ""
        }
    }

    private suspend fun runLimitedAsync(
        concurrency: Int = 10, vararg tasks: suspend () -> Unit
    ) = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope

        val semaphore = Semaphore(concurrency)

        tasks.map { task ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        task()
                    } catch (e: Exception) {
                        Log.e(name, "Task failed: ${e.message}")
                    }
                }
            }
        }.awaitAll()
    }

    data class PlayerResponse(
        @JsonProperty("player_enc") val playerEnc: String? = null,
        @JsonProperty("next_pt") val nextPt: String? = null,
        @JsonProperty("next_pk") val nextPk: String? = null
    )
}