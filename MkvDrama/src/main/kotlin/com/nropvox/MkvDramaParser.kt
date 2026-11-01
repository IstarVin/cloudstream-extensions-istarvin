package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

internal data class MkvTitleCard(val title: String, val url: String, val movie: Boolean, val poster: String?)
internal data class MkvCatalogPage(val items: List<MkvTitleCard>, val hasNext: Boolean)
internal data class MkvTitleDetails(
    val title: String, val movie: Boolean, val poster: String?, val year: Int?,
    val plot: String?, val genres: List<String>, val actors: List<String>,
    val rating: Double?, val duration: Int?, val status: String?,
)

/** The public HTML pages work without the key required by the JSON search API. */
internal object MkvDramaParser {
    private fun image(element: Element?): String? = element?.let { img ->
        listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
            img.absUrl(attr).takeIf(MkvDramaDecoder::isHttp)
        }
    }

    fun catalog(html: String, pageUrl: String): MkvCatalogPage {
        val document = Jsoup.parse(html, pageUrl)
        val items = document.select("article.bs a.tip[href]").mapNotNull { anchor ->
            val url = anchor.absUrl("href")
            if (!MkvDramaDecoder.isHttp(url) || URI(url).host != URI(pageUrl).host) return@mapNotNull null
            val title = anchor.attr("title").ifBlank { anchor.selectFirst("h2")?.text().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            MkvTitleCard(title, url, anchor.selectFirst(".typez")?.text().equals("Movie", true), image(anchor.selectFirst("img")))
        }.distinctBy { it.url }
        check(document.selectFirst(".listupd") != null) { "Unrecognized MKVDrama catalog page" }
        return MkvCatalogPage(items, document.selectFirst(".hpage a.page-next[href]") != null)
    }

    fun details(html: String, pageUrl: String): MkvTitleDetails {
        val document = Jsoup.parse(html, pageUrl)
        val root = document.selectFirst(".bigcontent") ?: error("MKVDrama title metadata is unavailable")
        val title = root.selectFirst("h1.entry-title")?.text()?.takeIf(String::isNotBlank)
            ?: error("MKVDrama title metadata is unavailable")
        val fields = root.select(".infox .spe > span").mapNotNull { row ->
            val label = row.selectFirst("b")?.text()?.trim()?.removeSuffix(":")?.lowercase() ?: return@mapNotNull null
            label to row
        }.toMap()
        fun field(name: String): String? = fields[name]?.clone()?.apply { select("b, i").remove() }
            ?.text()?.takeIf(String::isNotBlank)
        val aired = field("aired") ?: field("released")
        val duration = field("duration").orEmpty()
        val hours = Regex("(?i)(\\d+)\\s*(?:hr|hour)").find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(?i)(\\d+)\\s*min").find(duration)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return MkvTitleDetails(
            title = title,
            movie = field("type").equals("Movie", true),
            poster = image(root.selectFirst(".thumb img")),
            // datePublished is the site's post date, not the title's release year.
            year = aired?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() },
            plot = document.selectFirst(".bixbox.synp .entry-content")?.text()?.takeIf(String::isNotBlank),
            genres = root.select(".genxed a").map { it.text() }.filter(String::isNotBlank).distinct(),
            actors = (fields["casts"] ?: fields["cast"])?.select("a")?.map { it.text() }
                ?.filter(String::isNotBlank)?.distinct().orEmpty(),
            rating = root.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 },
            duration = (hours * 60 + minutes).takeIf { it > 0 },
            status = field("status")?.lowercase(),
        )
    }
}
