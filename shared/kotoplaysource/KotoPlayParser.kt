package com.nropvox

import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure helpers, kept apart from [KotoPlay] so they can be unit tested without the app. */
object KotoPlayParser {
    const val SITE = "https://kotoplay.to"

    /** The CDN, the segments and the subtitle files all 403 without this; the site's own origin is rejected too. */
    const val MEGAPLAY_REFERER = "https://megaplay.buzz/"

    // The player's hard-coded AES key and IV (e1-player.min.js); both are zero-padded to 32 / 16 bytes.
    private const val ENC_KEY = "i?LMTAx0Q6,:}50U"
    private const val ENC_IV = "W0;27ToaUpl_P%'c"

    private val titleSlug = Regex("""^/(?:watch|anime)/([^/?#]+)""")
    private val embedId = Regex("""data-id="(\d+)"""")
    private val fileUrl = Regex(""""file"\s*:\s*"([^"]+)"""")
    private val streamInf = Regex("""RESOLUTION=\d+x(\d+)""")
    private val plainEpisodeName = Regex("""(?i)^(?:episode\s*)?(\d+)$""")

    /** `https://kotoplay.to/watch/naruto-shippuden-c8gov` (or `/anime/<slug>`) to the title slug. */
    fun slugOf(url: String): String? {
        val path = url.substringAfter("://").substringAfter('/', "").let { "/$it" }
        return titleSlug.find(path)?.groupValues?.get(1)
    }

    fun titleUrl(slug: String) = "$SITE/watch/$slug"

    /** Episode data: a full URL naming the title, the site's episode id and the audio wanted (`sub`, `dub`, `all`). */
    fun watchUrl(slug: String, chapterId: Int, lang: String) = "${titleUrl(slug)}?ch=$chapterId&lang=$lang"

    class WatchData(val slug: String, val chapterId: Int, val lang: String)

    fun parseWatchUrl(url: String): WatchData? {
        val slug = slugOf(url) ?: return null
        val query = url.substringAfter('?', "").substringBefore('#').split('&')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val chapterId = query["ch"]?.toIntOrNull() ?: return null
        return WatchData(slug, chapterId, query["lang"] ?: "sub")
    }

    /**
     * Each audio lists the same video on three CDNs: no `s=` (nexabloom, plain MPEG-TS under fake file names),
     * `s=tcdn` and `s=bcdn` (segments wrapped in a PNG header). Only the first plays without unwrapping.
     */
    fun isPlainServer(link: String): Boolean = !Regex("""[?&]s=""").containsMatchIn(link)

    /** The player's numeric id for [embedHtml], which differs between the sub and dub embed of one episode. */
    fun embedId(embedHtml: String): String? = embedId.find(embedHtml)?.groupValues?.get(1)

    private fun padded(text: String, size: Int) = text.toByteArray().copyOf(size)

    /** `getSources` carries `{"file": "<master.m3u8>"}` as base64url AES-256-CBC in its `enc` field. */
    fun decodeSourcesEnc(enc: String): String? {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(padded(ENC_KEY, 32), "AES"), IvParameterSpec(padded(ENC_IV, 16)))
        val plain = String(cipher.doFinal(Base64.getUrlDecoder().decode(enc.trim())))
        return fileUrl.find(plain)?.groupValues?.get(1)?.replace("\\/", "/")
    }

    /** The tallest `RESOLUTION` in a master, as the quality of the link. */
    fun topHeight(master: String): Int? = streamInf.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

    class Variant(val height: Int, val url: String)

    /**
     * The master's variants as absolute media playlist URLs, tallest first. Only the first variant of a master names its
     * `CODECS`; the player's FFmpeg video renderer throws a NullPointerException on a track without a MIME type, so the
     * links point at the media playlists (whose tracks the player reads from the stream) rather than at the master.
     */
    fun variants(master: String, masterUrl: String): List<Variant> {
        val base = URI(masterUrl)
        val lines = master.lines().map { it.trim() }
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val height = streamInf.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapIndexedNotNull null
            val uri = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            Variant(height, base.resolve(uri).toString())
        }.sortedByDescending { it.height }
    }

    /** Plots are plain text with `\r\n` and sometimes a trailing `[Written by MAL Rewrite]` credit. */
    fun plainText(text: String?): String? = text?.replace("\r\n", "\n")
        ?.replace(Regex("""\s*\[Written by [^\]]*\]\s*$"""), "")?.trim()?.takeIf { it.isNotEmpty() }

    /** The episode's own title, or null when it is just `Episode 12` / `12`, which the app already shows. */
    fun episodeName(title: String?, number: String?): String? {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val plain = plainEpisodeName.find(trimmed)?.groupValues?.get(1)
        return if (plain != null && plain == number) null else trimmed
    }

    /** Cards of a list response in the order the site gave in `ids`, falling back to the order of `anime`. */
    fun ordered(response: KpBrowse): List<KpAnime> {
        val cards = response.anime.orEmpty()
        val ids = response.ids ?: return cards
        val byId = cards.filter { it.id != null }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }.ifEmpty { cards }
    }

    /** The `/data/anime` entry for [slug]: the only one with `chapters` and the full `description`. */
    fun titleOf(details: KpDetails, slug: String): KpAnime? = details.anime.orEmpty().firstOrNull { it.slug == slug }

    /** Related and other-season cards of a title, minus the title itself, each once. */
    fun relatedOf(details: KpDetails, slug: String): List<KpAnime> {
        val wanted = (details.seasons.orEmpty() + details.related.orEmpty()).toSet()
        return details.anime.orEmpty().filter { it.id in wanted && it.slug != slug }.distinctBy { it.id }
    }

    /**
     * The card of AniList entry [anilistId] among search results. `ani_id` is the AniList id (it differs from `mal_id`
     * for everything newer than the 2000s) but is missing on some cards, which then still carry the MAL id ([malId], 0
     * when unknown).
     */
    fun pickByAniList(cards: List<KpAnime>, anilistId: Int, malId: Int): KpAnime? =
        cards.firstOrNull { it.ani_id == anilistId }
            ?: cards.firstOrNull { it.ani_id == null && malId > 0 && it.mal_id == malId }

    /** The chapter numbered [episode]. Each title numbers its chapters from 1, like an AniList entry's episodes. */
    fun chapterFor(chapters: List<KpChapter>, episode: Int): KpChapter? =
        chapters.firstOrNull { it.id != null && it.chapter_number?.trim()?.toIntOrNull() == episode }

    /** Site spells movie as both `Movie` and `MOVIE`. */
    fun isMovie(type: String?) = type.equals("movie", ignoreCase = true)

    fun isOva(type: String?) = type.equals("ova", ignoreCase = true) || type.equals("special", ignoreCase = true) ||
        type.equals("tv special", ignoreCase = true)
}
