package com.istarvin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
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

        val searchDoc = app.get("$mainUrl/search?q=$code").document
        val matchedUrl =
            searchDoc.selectFirst("article.front-video-card:not(.front-partner-card) a")
                ?.attr("href")?.let { fixUrl(it) }
                ?: return

        val res = app.get(matchedUrl).text
        val configRaw = res.substringAfter("id=\"frontWatchConfig\" type=\"application/json\">")
            .substringBefore("</script>")

        val configData = parseJson<WatchConfig>(configRaw)

        configData.videoTitle?.substringBefore(" ")?.let { code ->
            getExtractorApiFromName("SubtitleCat").getUrl(
                url = code,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        configData.playerSources?.forEach { source ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source.src
                ) {
                    this.quality = source.size ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.type =
                        if (source.src.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
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

    private val apiKey =
        "Y1ZGUWNVSnROVlJOTUVWMlZsUldVMjlsWjBGelFUMDk6T0ZaNksxQmhjVTFhTHpCdFlWZDFNbE5CUm01Qlp6MDk="

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

        val searchDoc = app.get("$mainUrl/search/$code").document
        val matchedUrl = searchDoc.selectFirst("div.tray-item a")
            ?.attr("href")?.let { fixUrl(it) }
            ?: return

        val res = app.get(matchedUrl, headers = commonHeaders)

        val tasks = mutableListOf<suspend () -> Unit>()

        tasks.add(
            suspend {
                res.document.select(".full-text-desc").text().substringBefore(" ").let { code ->
                    getExtractorApiFromName("SubtitleCat").getUrl(
                        url = code,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }
        )

        res.document.select(".episode-list button.btn-player").forEach { ep ->
            tasks.add(suspend suspend@{
                val res = app.get(matchedUrl, headers = commonHeaders)

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
                Log.d("SexTB", episodeId)

                try {
                    val postData = mapOf(
                        "episode" to episodeId, "filmId" to sourceId, "pt" to currentPt
                    )

                    val ajaxResponse = app.post(
                        "${mainUrl}/ajax/player", headers = mapOf(
                            "Referer" to matchedUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Authorization" to "Basic $apiKey",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "Accept" to "*/*",
                            "Origin" to mainUrl
                        ), data = postData
                    )

                    Log.d("SexTB", "${ep.text().trim()} ${ajaxResponse.text}")

                    val responseData = ajaxResponse.parsedSafe<PlayerResponse>()
                    val encryptedPlayer = responseData?.playerEnc
                    val key = currentPk ?: ""

                    if (encryptedPlayer != null && key.isNotEmpty()) {
                        val decryptedRaw = decryptPlayer(encryptedPlayer, key)
                        Log.d("SexTB", decryptedRaw)

                        val iframeUrl =
                            Regex("""src=\\?["'](https:.*?)(?:\?|\\?["']|["'])""").find(decryptedRaw)?.groupValues?.get(
                                1
                            )?.replace("\\/", "/")

                        if (iframeUrl != null && !iframeUrl.contains("upgrade")) {
                            loadExtractor(iframeUrl, matchedUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("SexTB", "${e.message}")
                }
            })
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

        val searchDoc = app.get("$mainUrl/en/search/$code").document
        val matchedUrl = searchDoc.selectFirst("div.grid.grid-cols-2 > div a")
            ?.attr("href")?.let { fixUrl(it) }
            ?: return

        val unpacked = app.get(matchedUrl).text.let { getAndUnpack(it) }

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