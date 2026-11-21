package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * kotoplay.to's stream chain: `/data/servers` lists the MegaPlay embeds of an episode, the embed page carries the
 * player id, `stream/getSources` answers with an AES-encrypted master URL, and the playlist, its segments and the
 * subtitles only need a megaplay `Referer`. Also finds a title by AniList id (the site can't look one up by id, but its
 * cards carry `ani_id`, so a title search filtered on it is exact). Shared by the KotoPlay and OmniStream plugins.
 */
object KotoPlaySource {
    private const val TAG = "KotoPlay"
    private const val MAX_TITLE_SEARCHES = 4
    val json = mapOf("Accept" to "application/json")
    private val megaplayHeaders = mapOf("Referer" to KotoPlayParser.MEGAPLAY_REFERER)

    // A title's slug never changes, so an AniList id is looked up once per session.
    private val slugs = ConcurrentHashMap<Int, String>()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** The `/data/anime` response for [slug]. */
    suspend fun details(slug: String): KpDetails? =
        tryParseJson<KpDetails>(app.get("${KotoPlayParser.SITE}/data/anime?slug=${encode(slug)}", headers = json).text)

    /** One page of `/data/browse` for the query string [query] (already encoded). */
    suspend fun browse(query: String, page: Int): KpBrowse? =
        tryParseJson<KpBrowse>(app.get("${KotoPlayParser.SITE}/data/browse?$query&page=$page", headers = json).text)

    /** The slug of the AniList entry [anilistId] (or MAL [malId] when the site has no AniList id), searched by [titles]. */
    suspend fun slugFor(anilistId: Int, malId: Int, titles: List<String>): String? {
        slugs[anilistId]?.let { return it }
        for (title in titles.filter { it.isNotBlank() }.distinct().take(MAX_TITLE_SEARCHES)) {
            val cards = try {
                browse("keyword=${encode(title.trim())}&sort=most_viewed", 1)?.let { KotoPlayParser.ordered(it) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search for $title: $e")
                continue
            }
            val slug = KotoPlayParser.pickByAniList(cards, anilistId, malId)?.slug ?: continue
            slugs[anilistId] = slug
            return slug
        }
        return null
    }

    /**
     * An AniList entry's [episode] (numbered within the entry, which is how the site numbers each of its titles).
     * Null when the title isn't on the site or has no such episode.
     */
    suspend fun load(
        anilistId: Int,
        malId: Int,
        titles: List<String>,
        episode: Int,
        langs: List<String>,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = slugFor(anilistId, malId, titles) ?: return false
        val chapters = details(slug)?.let { KotoPlayParser.titleOf(it, slug) }?.chapters.orEmpty()
        val chapter = KotoPlayParser.chapterFor(chapters, episode)?.id ?: return false
        return loadChapter(chapter, langs, name, subtitleCallback, callback)
    }

    /** Emits a link per quality for each of [langs] (`sub`, `dub`) that the site lists for [chapterId]. */
    suspend fun loadChapter(
        chapterId: Int,
        langs: List<String>,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = tryParseJson<KpServers>(app.get("${KotoPlayParser.SITE}/data/servers?chapter=$chapterId", headers = json).text)
            ?.servers.orEmpty()

        val found = AtomicBoolean(false)
        val subtitlesSent = AtomicBoolean(false)
        langs.filter { servers.containsKey(it) }.amap { lang ->
            // Only the plain server plays without unwrapping its segments (see KotoPlayParser.isPlainServer).
            val server = servers[lang].orEmpty().firstOrNull { !it.link.isNullOrBlank() && KotoPlayParser.isPlainServer(it.link) }
                ?: return@amap
            runCatching {
                val embedUrl = server.link!!
                val embedId = KotoPlayParser.embedId(app.get(embedUrl, referer = "${KotoPlayParser.SITE}/").text)
                    ?: throw IllegalStateException("embed has no player id")
                val sources = tryParseJson<KpSources>(
                    app.get(
                        "${KotoPlayParser.MEGAPLAY_REFERER}stream/getSources?id=$embedId",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to embedUrl)
                    ).text
                ) ?: throw IllegalStateException("getSources was not JSON")
                // Sent before the playlist step so a slow or dead stream doesn't lose them. Sub and dub list the same tracks.
                if (subtitlesSent.compareAndSet(false, true)) {
                    for (track in sources.tracks.orEmpty()) {
                        val file = track.file ?: continue
                        if (track.kind != "captions") continue
                        subtitleCallback(newSubtitleFile(track.label ?: "Subtitles", file) { headers = megaplayHeaders })
                    }
                }
                val master = KotoPlayParser.decodeSourcesEnc(sources.enc ?: throw IllegalStateException("getSources has no enc"))
                    ?: throw IllegalStateException("enc has no file")
                val masterBody = app.get(master, headers = megaplayHeaders).text
                if (!masterBody.startsWith("#EXTM3U")) throw IllegalStateException("master is not a playlist")

                val label = "$name ${if (lang == "dub") "DUB" else "SUB"}"
                // One link per quality: see KotoPlayParser.variants. Segments are named .jpg/.html/.js, so M3u8Helper
                // would drop the playlist; the links are emitted as they are.
                val variants = KotoPlayParser.variants(masterBody, master).ifEmpty { listOf(KotoPlayParser.Variant(0, master)) }
                for (variant in variants) {
                    callback(newExtractorLink(label, label, variant.url, ExtractorLinkType.M3U8) {
                        referer = KotoPlayParser.MEGAPLAY_REFERER
                        headers = megaplayHeaders
                        quality = variant.height
                    })
                }
                found.set(true)
            }.onFailure { Log.w(TAG, "$lang for chapter $chapterId: $it") }
        }
        return found.get()
    }
}
