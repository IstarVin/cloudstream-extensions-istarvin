package com.nropvox

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder

/**
 * kotoplay.to is a static shell over a public JSON API under `/data/<name>` (browse with server-side filters and sort,
 * suggest, anime with its flat chapter list, servers). Streams are `megaplay.buzz` embeds: the embed page carries a
 * numeric id, `stream/getSources` answers with an AES-encrypted master URL ([KotoPlayParser.decodeSourcesEnc]), and
 * the playlist, its segments and the subtitles only need a megaplay `Referer`. Sub and dub are separate files. The
 * stream chain is [KotoPlaySource] in `shared/kotoplaysource` (shared with OmniStream).
 */
class KotoPlay : MainAPI() {
    override var mainUrl = KotoPlayParser.SITE
    override var name = "KotoPlay"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Every row is a /data/browse query, so each one pages. Without a `sort` or `view` the site's order is random.
    override val mainPage = mainPageOf(
        "view=trending" to "Trending",
        "sort=recently_updated" to "Latest Episodes",
        "sort=recently_updated&language=dub" to "Latest Dubs",
        "sort=recently_added" to "New on KotoPlay",
        "sort=most_viewed" to "Most Popular",
        "type=movie&sort=most_viewed" to "Popular Movies",
        "view=upcoming" to "Upcoming",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = browse(request.data, page) ?: throw ErrorLoadingException("Could not load ${request.name}")
        return newHomePageResponse(
            request.name,
            KotoPlayParser.ordered(result).mapNotNull { it.toSearchResponse() },
            hasNext = result.has_more == true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val result = browse("keyword=${encode(query.trim())}", page)
        return newSearchResponseList(
            result?.let { KotoPlayParser.ordered(it) }.orEmpty().mapNotNull { it.toSearchResponse() },
            hasNext = result?.has_more == true
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return tryParseJson<KpBrowse>(app.get("$mainUrl/data/suggest?q=${encode(query.trim())}", headers = KotoPlaySource.json).text)
            ?.anime.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    private suspend fun browse(query: String, page: Int): KpBrowse? = KotoPlaySource.browse(query, page)

    override suspend fun load(url: String): LoadResponse {
        val slug = KotoPlayParser.slugOf(url) ?: throw ErrorLoadingException("Not a $name URL: $url")
        val details = KotoPlaySource.details(slug) ?: throw ErrorLoadingException("Could not load $name title $slug")
        val anime = KotoPlayParser.titleOf(details, slug) ?: throw ErrorLoadingException("$name has no title $slug")
        val title = anime.title?.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("$name title $slug has no name")

        // One flat list, absolute-numbered; every season is its own title on the site.
        val chapters = anime.chapters.orEmpty().filter { it.id != null }
        val pageUrl = KotoPlayParser.titleUrl(slug)
        val type = tvType(anime.type)
        val related = KotoPlayParser.relatedOf(details, slug).mapNotNull { it.toSearchResponse() }

        fun LoadResponse.fill() {
            posterUrl = anime.poster
            backgroundPosterUrl = anime.background_image
            plot = KotoPlayParser.plainText(anime.description ?: anime.short_description)
            year = anime.year
            tags = anime.genres.orEmpty().mapNotNull { it.name }
            score = anime.score?.let { Score.from10(it) }
            recommendations = related
            anime.ani_id?.takeIf { it > 0 }?.let { addAniListId(it) }
            anime.mal_id?.takeIf { it > 0 }?.let { addMalId(it) }
        }

        if (type == TvType.AnimeMovie && chapters.size <= 1) {
            val data = KotoPlayParser.watchUrl(slug, chapters.firstOrNull()?.id ?: throw ErrorLoadingException("$name title $slug has no video"), "all")
            return newMovieLoadResponse(title, pageUrl, type, data) { fill() }
        }

        fun episodesFor(lang: String, available: (KpChapter) -> Boolean) = chapters.filter(available).map { chapter ->
            newEpisode(KotoPlayParser.watchUrl(slug, chapter.id!!, lang)) {
                episode = chapter.chapter_number?.toIntOrNull()
                this.name = KotoPlayParser.episodeName(chapter.title, chapter.chapter_number)
            }
        }
        val subbed = episodesFor("sub") { it.is_sub != 0 }
        val dubbed = episodesFor("dub") { it.is_dub == 1 }

        return newAnimeLoadResponse(title, pageUrl, type) {
            fill()
            japName = anime.alternative?.takeIf { it.isNotBlank() && it != title }
            synonyms = anime.titles?.split(';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != title }
            duration = Regex("""\d+""").find(anime.duration.orEmpty())?.value?.toIntOrNull()
            showStatus = when (anime.status) {
                "Currently Airing" -> ShowStatus.Ongoing
                "Finished Airing" -> ShowStatus.Completed
                else -> null
            }
            comingSoon = subbed.isEmpty() && dubbed.isEmpty()
            addEpisodes(DubStatus.Subbed, subbed)
            addEpisodes(DubStatus.Dubbed, dubbed)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watch = KotoPlayParser.parseWatchUrl(data) ?: return false
        val langs = if (watch.lang == "all") listOf("sub", "dub") else listOf(watch.lang)
        return KotoPlaySource.loadChapter(watch.chapterId, langs, name, subtitleCallback, callback)
    }

    private fun KpAnime.toSearchResponse(): SearchResponse? {
        val slug = slug ?: return null
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val card = this
        return newAnimeSearchResponse(title, KotoPlayParser.titleUrl(slug), tvType(type)) {
            posterUrl = card.poster
            year = card.year
            val subs = card.sub_count ?: 0
            val dubs = card.dub_count ?: 0
            addDubStatus(dubs > 0, subs > 0, dubs.takeIf { it > 0 }, subs.takeIf { it > 0 })
        }
    }

    private fun tvType(type: String?) = when {
        KotoPlayParser.isMovie(type) -> TvType.AnimeMovie
        KotoPlayParser.isOva(type) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
