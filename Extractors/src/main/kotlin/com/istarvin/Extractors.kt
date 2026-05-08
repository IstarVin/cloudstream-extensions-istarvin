package com.istarvin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class LuluVid : StreamWishExtractor() {
    override val name = "LuluStream"
    override val mainUrl = "https://luluvid.com"
}

class Vidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return

        val res = app.post("https://vidara.so/api/stream", json = mapOf("filecode" to id))
            .parsedSafe<Result>() ?: return

        generateM3u8(name, res.url, mainUrl)
            .forEach(callback)
    }

    data class Result(
        @JsonProperty("streaming_url") val url: String
    )
}

class RubyVidHub : ExtractorApi() {
    override var name = "RubyVidHub"
    override var mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = false

    private val m3u8Regex = Regex("""[:=]\s*"([^"\s]+(\.m3u8|master\.txt)[^"\s]*)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return

        val text = app.post(
            "$mainUrl/dl",
            data = mapOf("file_code" to id, "op" to "embed")
        ).text

        val res = getAndUnpack(text)

        m3u8Regex.findAll(res).forEach { match ->
            generateM3u8(
                source = name,
                streamUrl = match.groupValues[1],
                referer = mainUrl
            ).forEach(callback)
        }
    }
}

class SubtitleCat : ExtractorApi() {
    override val name = "SubtitleCat"
    override val mainUrl = "https://subtitlecat.com"
    override val requiresReferer = false

    private fun String.toAlphaNumeric(): String {
        return this.filter { c -> c.isLetterOrDigit() }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val query = url.substringAfter("query=")
        val queryUrl = "${mainUrl}/index.php?search=$query"
        val doc = app.get(queryUrl).document
        val subs = doc.select(".sub-table a")
            .map { mainUrl + '/' + it.attr("href") }
            .take(3)
            .filter {
                it.toAlphaNumeric().contains(query.toAlphaNumeric())
            }
            .ifEmpty { return }

        coroutineScope {
            subs.map { subUrl ->
                async {
                    val subPageDoc = app.get(subUrl).document
                    val href =
                        subPageDoc.getElementById("download_en")?.attr("href") ?: return@async null

                    subtitleCallback(newSubtitleFile("English", mainUrl + href))
                }
            }
        }
    }
}