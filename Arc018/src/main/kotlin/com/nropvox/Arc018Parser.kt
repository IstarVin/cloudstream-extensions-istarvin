package com.nropvox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

internal data class Arc018Card(val title: String, val url: String, val poster: String?, val series: Boolean)
internal data class Arc018Episode(val url: String, val season: Int, val episode: Int)
internal data class Arc018Details(
    val title: String, val poster: String?, val plot: String?, val year: Int?,
    val genres: List<String>, val actors: List<String>, val episodes: List<Arc018Episode>,
    val backdrop: String?, val duration: Int?, val imdb: String?, val series: Boolean,
)
internal data class Arc018Server(val name: String, val link: String)
internal data class Arc018Track(val label: String, val url: String)

/** Pure parsing of the site's HTML, AJAX server lists, and Vidmoly player configuration. */
internal object Arc018Parser {
    private val mapper = jacksonObjectMapper()
    private val episodePattern = Regex("/s(\\d+)-e(\\d+)/?(?:[?#].*)?$", RegexOption.IGNORE_CASE)
    private val filePattern = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")

    fun cards(document: Document): List<Arc018Card> = document.select("article").mapNotNull { article ->
        val anchor = article.closest("a[href]") ?: article.selectFirst("a[href]") ?: return@mapNotNull null
        val url = anchor.absUrl("href")
        if (!url.contains("/watch-movie/") && !url.contains("/watch-series/")) return@mapNotNull null
        val title = article.selectFirst("h3")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return@mapNotNull null
        Arc018Card(title, url, article.selectFirst("img[src]")?.absUrl("src"), url.contains("/watch-series/"))
    }.distinctBy { it.url }

    fun hasNext(document: Document, page: Int): Boolean = document.select("a[href]").any {
        val url = it.absUrl("href")
        val next = Regex("/page/(\\d+)/").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
        next == page + 1
    }

    fun episode(url: String): Arc018Episode? {
        val match = episodePattern.find(url) ?: return null
        return Arc018Episode(url, match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    fun details(document: Document): Arc018Details? {
        val info = document.select("script[type=application/ld+json]").mapNotNull {
            runCatching { mapper.readTree(it.data()) }.getOrNull()
        }.firstOrNull { it.path("@type").asText() in listOf("Movie", "TVSeries") } ?: return null
        val title = info.path("name").asText().trim().ifEmpty { return null }
        val year = info.path("datePublished").asText().take(4).toIntOrNull()
            ?: document.selectFirst("a[href*=/year/]")?.text()?.toIntOrNull()
        val episodes = document.select("a.ep-pill[href]").mapNotNull { episode(it.absUrl("href")) }
            .distinctBy { it.url }.sortedWith(compareBy({ it.season }, { it.episode }))
        return Arc018Details(
            title, info.path("image").asText().ifBlank { null },
            info.path("description").asText().ifBlank { null }, year,
            info.path("genre").map { it.asText() }, document.select("[aria-label=Cast] a").map { it.text() },
            episodes, document.selectFirst("[data-backdrop]")?.attr("data-backdrop")?.ifBlank { null },
            Regex("PT(\\d+)M").matchEntire(info.path("duration").asText())?.groupValues?.get(1)?.toIntOrNull(),
            document.selectFirst("a[href*=imdb.com/title/]")?.attr("href"), info.path("@type").asText() == "TVSeries",
        )
    }

    fun playerToken(document: Document): String? = document.selectFirst("section[data-token]")
        ?.attr("data-token")?.takeIf { it.isNotBlank() }

    fun servers(json: String): List<Arc018Server> {
        val root = mapper.readTree(json)
        val nodes = if (root.isArray) root.toList() else listOf(root)
        return nodes.mapNotNull {
            val url = it.path("link").asText()
            if (!url.startsWith("https://")) null else Arc018Server(it.path("name").asText(), url)
        }.distinctBy { it.link }
    }

    fun vidmolyStreams(html: String): List<String> = filePattern.findAll(html).map { it.groupValues[1] }
        .filter { it.startsWith("https://") && it.contains(".m3u8") }.distinct().toList()

    fun subtitleEndpoint(url: String): String? = URI(url).rawQuery?.split('&')?.mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size == 2 && parts[0] in listOf("sub.info", "subget")) URLDecoder.decode(parts[1], "UTF-8") else null
    }?.firstOrNull { it.startsWith("https://") }

    fun tracks(json: String): List<Arc018Track> = mapper.readTree(json).takeIf { it.isArray }?.mapNotNull {
        val url = it.path("file").asText()
        if (!url.startsWith("https://")) null else Arc018Track(it.path("label").asText().ifBlank { "English" }, url)
    }.orEmpty()
}
