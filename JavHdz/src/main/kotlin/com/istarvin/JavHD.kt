package com.istarvin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.io.encoding.Base64

private const val TRANSLATION_CACHE_KEY = "javhd_translated_titles"

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

    private val googleTranslateApiKey = BuildConfig.GOOGLE_TRANSLATE_API_KEY
    private var translatedTitleCache: MutableMap<String, String>? = null

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

    private suspend fun Element.mainPageResults(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val title = translateVietnameseTitle(link.attr("title").trim())
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img") ?: return null
        val poster = fixUrl(img.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private suspend fun translateVietnameseTitle(title: String): String {
        if (title.isBlank() || googleTranslateApiKey.isBlank()) return title

        val cache = getTranslatedTitleCache()
        cache[title]?.let { return it }

        val translatedTitle = runCatching {
            val encodedTitle = withContext(Dispatchers.IO) {
                URLEncoder.encode(title, "utf-8")
            }
            val response = app.get(
                "https://translation.googleapis.com/language/translate/v2" +
                        "?key=$googleTranslateApiKey&q=$encodedTitle&source=vi&target=en&format=text"
            ).text

            parseJson<GoogleTranslateResponse>(response).data?.translations
                ?.firstOrNull()?.translatedText?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return title

        cache[title] = translatedTitle
        setKey(TRANSLATION_CACHE_KEY, cache)
        return translatedTitle
    }

    private fun getTranslatedTitleCache(): MutableMap<String, String> {
        translatedTitleCache?.let { return it }

        val cache = (getKey<Map<String, String>>(TRANSLATION_CACHE_KEY, emptyMap()) ?: emptyMap())
            .toMutableMap()
        translatedTitleCache = cache
        return cache
    }


    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.header-title > a")?.text()?.trim() ?: return null
        val title = translateVietnameseTitle(rawTitle)
        val code =
            document.selectFirst("#film-content-wrapper")?.text()?.trim()?.substringBefore(" ")
        val poster = fixUrlNull(document.selectFirst("img.thumb")?.attr("src"))

        val data = document.select(".user-action > .server").mapNotNull {
            val onclick = it.attr("onclick").substringAfter("(")
            val id = onclick.substringBefore(",")
            val serverNum = onclick.substringAfter(",").substringBefore(")")
            id to serverNum
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            data = DataObject(code, data).toJson()
        ) {
            this.posterUrl = poster
        }
    }

    private val getBase64LinkRegex = Regex("""window\.atob\("(.+?)"\)""")
    private val urlRegex = Regex("""(https?://)?([\da-z.-]+)\.([a-z.]{2,6})([/\w .-]*)*/?""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataObject = parseJson<DataObject>(data)

        val tasks = mutableListOf<suspend () -> Unit>()

        tasks.add(suspend suspend@{
            val code = dataObject.code ?: return@suspend
            getExtractorApiFromName("SubtitleCat").run {
                if (name != "SubtitleCat") {
                    return@run
                }

                getUrl(
                    url = code,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        })

        dataObject.data.forEach { (id, serverId) ->
            tasks.add(suspend suspend@{
                val res = app.post(
                    url = "$mainUrl/ajax",
                    data = mapOf(
                        "id" to id,
                        "server" to serverId
                    )
                ).parsedSafe<AjaxRes>() ?: return@suspend

                if ("jwplayer.js" in res.player) {
                    getBase64LinkRegex.find(res.player)?.groups?.get(1)?.value?.let {
                        val url = Base64.decode(it).decodeToString()

                        generateM3u8(
                            source = name,
                            streamUrl = url,
                            referer = mainUrl,
                            headers = mapOf("referer" to mainUrl)
                        ).forEach(callback)
                    }
                    return@suspend
                }

                urlRegex.find(res.player)?.value?.let {
                    loadExtractor(
                        url = it,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            })
        }

        runLimitedAsync(tasks = tasks.toTypedArray())

        return true
    }

    private suspend fun runLimitedAsync(
        concurrency: Int = 5,
        vararg tasks: suspend () -> Unit
    ) = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope

        val semaphore = Semaphore(concurrency)

        tasks.map { task ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        task()
                    } catch (e: Exception) {
                        Log.e("SulasokConcurrency", "Task failed: ${e.message}")
                    }
                }
            }
        }.awaitAll()
    }

    data class GoogleTranslateResponse(
        val data: GoogleTranslateData? = null
    )

    data class GoogleTranslateData(
        val translations: List<GoogleTranslateTranslation>? = null
    )

    data class GoogleTranslateTranslation(
        @JsonProperty("translatedText") val translatedText: String? = null
    )

    data class DataObject(
        val code: String?,
        val data: List<Pair<String, String>>
    )

    data class AjaxRes(val player: String)
}
