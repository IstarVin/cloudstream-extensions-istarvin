package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/** Catalog requests and fresh signed streams, shared by NetMirror and OmniStream. */
internal object NetMirrorSource {
    const val SITE = "https://netmirror.plus"
    const val API = "https://api2.imdb3.shop/api"
    private const val SEARCH_API = "https://api2.imdb4.shop/api/search2"
    private const val mediaReferer = "https://movieboxonline.net/"
    val headers get() = mapOf("Referer" to "$SITE/")

    suspend fun search(query: String, page: Int = 1): NetMirrorParser.Catalog =
        NetMirrorParser.catalog(app.get(
            "$SEARCH_API/${NetMirrorParser.encode(query.trim().lowercase())}?page=${(page - 1).coerceAtLeast(0)}", headers = headers
        ).text)

    suspend fun details(ref: NetMirrorParser.TitleRef): JsonNode =
        NetMirrorParser.catalog(app.get("$API/${ref.type}/${ref.id}", headers = headers).text).items.firstOrNull()
            ?: throw ErrorLoadingException("NetMirror could not find this title")

    suspend fun loadLinks(
        ref: NetMirrorParser.TitleRef,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (ref.type == "tv" && ref.episode <= 0) return false
        // Fetch details and sign at play time: saved episodes never contain expired media URLs.
        val item = details(ref)
        if (!NetMirrorParser.hasEpisode(item, ref)) return false
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
}
