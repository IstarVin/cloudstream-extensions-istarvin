package com.istarvin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element


private fun encodedSearchCode(code: String): String =
    URLEncoder.encode(code, "UTF-8")

private fun Element.matchesMovieCode(code: String): Boolean {
    if (code.isBlank()) return false
    val pattern = Regex(
        "(?<![a-z0-9])${Regex.escape(code)}(?![a-z0-9])",
        RegexOption.IGNORE_CASE
    )
    return pattern.containsMatchIn(text()) || select("a[href], img[alt]").any {
        pattern.containsMatchIn(it.attr("href")) || pattern.containsMatchIn(it.attr("alt"))
    }
}

class JavtifulExtractor : ExtractorApi() {
    override val name = "Javtiful"
    override val mainUrl = "https://javtiful.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringAfter("code=")

        val searchDoc = app.get("$mainUrl/search?q=${encodedSearchCode(code)}").document
        val matchedUrl = searchDoc.select("article.video-card:not(.video-card--partner)")
            .firstOrNull { it.matchesMovieCode(code) }
            ?.selectFirst("a.video-card__title")?.attr("href")?.let { fixUrl(it) }
            ?: return

        val res = app.get(matchedUrl).document
        val configRaw = res.selectFirst("script#watch-config")?.data()
            ?.takeIf { it.isNotBlank() } ?: return

        val configData = parseJson<WatchConfig>(configRaw)

        configData.videoTitle?.substringBefore(" ")?.let { code ->
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

        configData.playerSources.orEmpty().filter { it.src.isNotBlank() }.forEach { source ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    fixUrl(source.src)
                ) {
                    this.quality = source.size ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.type =
                        if (source.type?.contains("mpegurl", ignoreCase = true) == true ||
                            source.src.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                        ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }
    }

    data class WatchConfig(
        val playerSources: List<PlayerSource>? = null,
        val videoTitle: String? = null
    )

    data class PlayerSource(
        val src: String,
        val type: String? = null,
        val size: Int? = null
    )
}

class SexTBExtractor : ExtractorApi() {
    override val name = "SexTB"
    override var mainUrl = "https://sextb.net"
    override val requiresReferer = false

    private val cloudflareInterceptor = CloudflareKiller()

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringAfter("code=")

        val searchDoc = app.get(
            "$mainUrl/search/${encodedSearchCode(code).replace("+", "%20")}",
            headers = commonHeaders,
            interceptor = cloudflareInterceptor
        ).document
        val matchedUrl = searchDoc.select("div.tray-item")
            .firstOrNull { it.matchesMovieCode(code) }
            ?.selectFirst("a")?.attr("href")?.let { fixUrl(it) }
            ?: return

        val res = app.get(matchedUrl, headers = commonHeaders, interceptor = cloudflareInterceptor)
        val tasks = mutableListOf<suspend () -> Unit>()
        tasks.add(suspend {
            getExtractorApiFromName("SubtitleCat").takeIf { it.name == "SubtitleCat" }
                ?.getUrl(url = code, subtitleCallback = subtitleCallback, callback = callback)
        })

        val filmId = Regex("""var filmId\s*=\s*(\d+)""").find(res.text)?.groupValues?.get(1)
        var currentPt = Regex("""__pt\s*=\s*['"](.*?)['"]""").find(res.text)?.groupValues?.get(1)
        var currentPk = Regex("""__pk\s*=\s*['"](.*?)['"]""").find(res.text)?.groupValues?.get(1)
        val token = res.document.selectFirst("meta[name=_token]")?.attr("value")
        val socket = res.document.selectFirst("meta[name=_socket]")?.attr("value")
        val playerUrls = mutableSetOf<String>()
        res.document.selectFirst("#sextb-player iframe[src]")?.attr("src")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?.takeIf { !it.contains("upgrade") }?.let { iframeUrl ->
                playerUrls.add(iframeUrl)
                tasks.add(suspend { loadExtractor(iframeUrl, matchedUrl, subtitleCallback, callback) })
            }

        if (filmId != null && currentPt != null && currentPk != null && token != null && socket != null) {
            // AJAX requests share rotating session tokens and must stay sequential.
            for (ep in res.document.select(".episode-list button.btn-player:not(.vip)")) {
                try {
                    val response = app.post(
                        "$mainUrl/ajax/player",
                        headers = commonHeaders + mapOf(
                            "Referer" to matchedUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Authorization" to "Basic ${base64Encode("$token:$socket".toByteArray(Charsets.UTF_8))}",
                            "Origin" to mainUrl
                        ),
                        data = mapOf(
                            "episode" to ep.attr("data-id"),
                            "filmId" to ep.attr("data-source").ifEmpty { filmId },
                            "pt" to currentPt.orEmpty()
                        )
                    ).parsedSafe<PlayerResponse>() ?: continue
                    val decryptionKey = currentPk.orEmpty()
                    currentPt = response.nextPt ?: currentPt
                    currentPk = response.nextPk ?: currentPk
                    val encrypted = response.playerEnc ?: continue
                    val player = Jsoup.parse(decryptPlayer(encrypted, decryptionKey))
                    val iframeUrl = player.selectFirst("iframe[src]")?.attr("src")
                        ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: continue
                    if (iframeUrl.contains("upgrade") || !playerUrls.add(iframeUrl)) continue
                    tasks.add(suspend { loadExtractor(iframeUrl, matchedUrl, subtitleCallback, callback) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(name, "${e.message}")
                }
            }
        }

        JavStreamUtils.runLimitedAsync(10, tasks = tasks.toTypedArray())
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
            Log.d("SexTB", "${e.message}")
            ""
        }
    }

    data class PlayerResponse(
        @JsonProperty("player_enc") val playerEnc: String? = null,
        @JsonProperty("next_pt") val nextPt: String? = null,
        @JsonProperty("next_pk") val nextPk: String? = null
    )
}

class MissAvExtractor : ExtractorApi() {
    private val cloudflareInterceptor = CloudflareKiller()
    override val name = "MissAV"
    override var mainUrl = "https://missav.live"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringAfter("code=")

        val searchDoc = app.get(
            "$mainUrl/en/search/${encodedSearchCode(code).replace("+", "%20")}",
            interceptor = cloudflareInterceptor
        ).document
        val matchedUrl = searchDoc.select("div.grid.grid-cols-2 > div")
            .firstOrNull { it.matchesMovieCode(code) }
            ?.selectFirst("a[href*='/en/'], a[href*='/dm']")
            ?.attr("href")?.let { fixUrl(it) }
            ?: return

        val unpacked = app.get(matchedUrl, interceptor = cloudflareInterceptor).text.let { getAndUnpack(it) }

        val playlistId = """/([a-f0-9\-]{36})/""".toRegex().find(unpacked)?.groupValues?.get(1)

        if (playlistId != null) {
            generateM3u8(
                source = name,
                streamUrl = "https://surrit.com/$playlistId/playlist.m3u8",
                referer = "$mainUrl/",
                headers = mapOf("Referer" to "$mainUrl/")
            ).forEach(callback)
        }
    }
}

class JavGGStreamExtractor : ExtractorApi() {
    override val name = "JavGG"
    override var mainUrl = "https://javgg.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringAfter("code=")

        val searchDoc = app.get("$mainUrl/?s=$code").document
        val matchedUrl = searchDoc.selectFirst(".result-item > article a")
            ?.attr("href")?.let { fixUrl(it) }
            ?: return

        val document = app.get(matchedUrl).document

        val tasks =
            document.select(".pframe > iframe").map { el ->
                suspend {
                    val src = el.attr("src").ifEmpty { return@map }
                    loadExtractor(src, subtitleCallback, callback)
                    Unit
                }
            }

        JavStreamUtils.runLimitedAsync(tasks = tasks.toTypedArray())
    }
}

class SupJavExtractor : ExtractorApi() {
    override val name = "SupJav"
    override var mainUrl = "https://supjav.com"
    override val requiresReferer = false

    private val supjavApi = "https://lk1.supremejav.com/supjav.php"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.substringAfter("code=")

        val searchDoc = app.get("$mainUrl/?s=$code").document
        val matchedUrl = searchDoc.selectFirst(".post a")
            ?.attr("href")?.let { fixUrl(it) }
            ?: return

        val document = app.get(matchedUrl).document

        val tasks = document.select(".btn-server").map { btn ->
            suspend {
                val dataLink = btn.attr("data-link")

                val res1 = app.get(
                    url = "$supjavApi?l=$dataLink",
                    referer = mainUrl,
                    headers = mapOf("referer" to mainUrl)
                )

                val olid = res1.text.substringAfter("var OLID = '").substringBefore("'").reversed()
                val res2 = app.get(
                    "$supjavApi?c=$olid",
                    referer = res1.url,
                    headers = mapOf("referer" to res1.url),
                    allowRedirects = false
                )
                res2.headers["location"]?.let { loc ->
                    loadExtractor(
                        loc,
                        referer = supjavApi,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
                Unit
            }
        }

        JavStreamUtils.runLimitedAsync(tasks = tasks.toTypedArray())
    }
}