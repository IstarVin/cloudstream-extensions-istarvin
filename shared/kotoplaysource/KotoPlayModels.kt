package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class KpNamed(val slug: String? = null, val name: String? = null)

/**
 * The card shape shared by every `/data/<name>` response. Number-ish fields that the site sometimes sends as strings
 * (`episodes`, `score`) are typed String so one odd value doesn't null the whole object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KpAnime(
    val id: Int? = null,
    val slug: String? = null,
    val title: String? = null,
    val alternative: String? = null,
    val titles: String? = null,
    val poster: String? = null,
    val background_image: String? = null,
    val type: String? = null,
    val status: String? = null,
    val duration: String? = null,
    val score: String? = null,
    val year: Int? = null,
    val mal_id: Int? = null,
    val ani_id: Int? = null,
    val genres: List<KpNamed>? = null,
    val sub_count: Int? = null,
    val dub_count: Int? = null,
    val ep_count: Int? = null,
    val description: String? = null,
    val short_description: String? = null,
    /** Only on the requested title of `/data/anime`. */
    val chapters: List<KpChapter>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KpChapter(
    val id: Int? = null,
    val chapter_number: String? = null,
    val title: String? = null,
    val jp_title: String? = null,
    val is_filler: Int? = null,
    val is_sub: Int? = null,
    val is_dub: Int? = null,
)

/** `/data/home`: row name to ids, with every card in [anime]. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KpHome(val lists: Map<String, List<Int>>? = null, val anime: List<KpAnime>? = null)

/** `/data/browse` and `/data/suggest`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KpBrowse(
    val anime: List<KpAnime>? = null,
    val ids: List<Int>? = null,
    val page: Int? = null,
    val has_more: Boolean? = null,
)

/** `/data/anime?slug=`: the title (with `chapters`) is the entry whose slug matches; the rest are related cards. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KpDetails(
    val anime: List<KpAnime>? = null,
    val related: List<Int>? = null,
    val seasons: List<Int>? = null,
    val slug: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KpServer(val name: String? = null, val link: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KpServers(val ok: Boolean? = null, val servers: Map<String, List<KpServer>>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KpTrack(val file: String? = null, val label: String? = null, val kind: String? = null)

/** MegaPlay `getSources`: the stream URL itself is inside [enc]. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KpSources(val tracks: List<KpTrack>? = null, val enc: String? = null)
