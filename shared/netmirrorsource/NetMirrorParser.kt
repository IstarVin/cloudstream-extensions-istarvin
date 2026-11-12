package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.jsoup.Jsoup

/** Pure parsing of the site's JSON and Artplayer configuration. */
internal object NetMirrorParser {
    private val json = ObjectMapper()
    private val titlePath = Regex("""^/(movie|tv)/(\d+)/?$""")
    private val yearPattern = Regex("""\b(?:19|20)\d{2}\b""")
    private val option = Regex("""html\s*:\s*['"]([^'"\r\n]*)['"]\s*,\s*url\s*:\s*(?:encodeURI\s*)?\(?\s*['"](https?://[^'"\s]+)['"]""")
    private val fallbackVideo = Regex("""(?:const\s+vid_url2|(?:file|src|url)\s*)\s*[:=]\s*['"](https?://[^'"\s]+\.(?:mp4|m3u8)(?:\?[^'"\s]*)?)['"]""")

    data class TitleRef(val type: String, val id: String, val season: Int = 0, val episode: Int = 0)
    data class Catalog(val items: List<JsonNode>, val hasNext: Boolean)
    data class Media(val label: String, val url: String, val quality: Int = 0)
    data class Player(val videos: List<Media>, val subtitles: List<Media>)

    fun text(node: JsonNode, key: String): String = node.path(key).takeUnless { it.isNull || it.isMissingNode }?.asText()?.trim().orEmpty()

    fun catalog(body: String): Catalog {
        val root = json.readTree(body)
        val data = root.path("data").takeIf { it.isObject } ?: root
        val items = data.path("results").takeIf { it.isArray }
            ?: data.path("data").takeIf { it.isArray }
            ?: throw IllegalArgumentException("NetMirror returned no catalog")
        val pager = data.path("pager")
        return Catalog(items.toList(), pager.path("current_page").asInt() + 1 < pager.path("total_pages").asInt())
    }

    fun reference(url: String): TitleRef? = runCatching {
        val uri = URI(url)
        val match = titlePath.matchEntire(uri.path) ?: return null
        val params = uri.rawQuery.orEmpty().split('&').associate {
            val parts = it.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        TitleRef(match.groupValues[1], match.groupValues[2], params["season"]?.toIntOrNull() ?: 0, params["episode"]?.toIntOrNull() ?: 0)
    }.getOrNull()

    fun year(value: String): Int? = yearPattern.find(value)?.value?.toIntOrNull()

    /** Catalog names append language tags and season ranges, e.g. `Breaking Bad [Hindi] S1-S5`. */
    fun cleanTitle(value: String): String = Jsoup.parse(value).text()
        .replace(Regex("""(?i)\s+S\d+(?:\s*[-–]\s*S?\d+)?\s*$"""), "")
        .replace(Regex("""\s*\[[^\]]+]\s*$"""), "").trim()

    fun hasEpisode(title: JsonNode, ref: TitleRef): Boolean = ref.type == "movie" ||
        title.path("season").any { it.path("se").asInt() == ref.season && ref.episode in episodes(it) }

    fun episodes(season: JsonNode): List<Int> {
        val explicit = text(season, "allEp").ifBlank { text(season, "ep").takeIf { ',' in it }.orEmpty() }
        if (explicit.isNotBlank()) return explicit.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct().sorted()
        val count = text(season, "ep").toIntOrNull()?.coerceIn(0, 10000) ?: 0
        return (1..count).toList()
    }

    fun signature(id: String, timestamp: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("net###@@sss".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$id:$timestamp".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun playerPath(title: JsonNode, ref: TitleRef, timestamp: Long): String {
        val embed = title.path("embed_json").takeIf { it.isArray }?.firstOrNull {
            it.path("se").asInt() == ref.season && it.path("ep").asInt() == ref.episode
        }
        val tmdb = text(title, "tm_id")
        val params = linkedMapOf(
            "id" to text(title, "subjectid"), "se" to ref.season.toString(), "ep" to ref.episode.toString(),
            "dp" to text(title, "dp"), "na" to Base64.getEncoder().encodeToString(text(title, "title").toByteArray(Charsets.UTF_8)),
            "year" to (year(text(title, "release_date"))?.toString() ?: ""), "tm_id" to tmdb,
            "ts" to timestamp.toString(), "sig" to signature(tmdb.ifBlank { ref.id }, timestamp),
            "nid" to ref.id, "exten" to "false"
        )
        val player = if (embed != null) {
            params["url"] = text(embed, "url")
            params["size"] = text(embed, "size")
            params["name"] = text(embed, "name")
            text(embed, "name").takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: "watchbox"
        } else "watchbox"
        return "/play/$player.php?" + params.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
    }

    fun player(html: String): Player {
        // Comments contain stale example URLs and must not become sources.
        val script = Jsoup.parse(html).select("script").joinToString("\n") { it.data() }
            .lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val videos = mutableListOf<Media>()
        val subtitles = mutableListOf<Media>()
        option.findAll(script).forEach { match ->
            val label = Jsoup.parse(match.groupValues[1]).text()
            val url = match.groupValues[2].replace("\\/", "/").replace("&amp;", "&")
            val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
            when {
                path.endsWith(".srt") || path.endsWith(".vtt") -> subtitles.add(Media(label, url))
                path.endsWith(".mp4") || path.endsWith(".m3u8") -> videos.add(Media(label, url, Regex("\\d{3,4}").find(label)?.value?.toIntOrNull() ?: 0))
            }
        }
        if (videos.isEmpty()) fallbackVideo.findAll(script).forEach { videos.add(Media("Original", it.groupValues[1])) }
        return Player(videos.distinctBy { it.url }.sortedByDescending { it.quality }, subtitles.distinctBy { it.url })
    }
}
