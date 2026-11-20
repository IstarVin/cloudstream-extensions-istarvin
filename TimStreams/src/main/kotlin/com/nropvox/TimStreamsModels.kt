package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** One entry of `streams[]`: the iframe `src` the site's own player uses. VIP ones are paywalled. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TsStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("vip") val vip: Boolean = false,
)

/**
 * An event, channel or replay. They share this shape: events have `time` (no time zone) and `sub_genre`, channels
 * have `flag`, replays have `date` (MM/DD/YYYY). `genre` is Int-typed but read through a String so a quoted id can't null the object.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TsItem(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("sub_genre") val subGenre: String? = null,
    @JsonProperty("time") val time: String? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("flag") val flag: String? = null,
    @JsonProperty("vip") val vip: Boolean = false,
    @JsonProperty("featured") val featured: Boolean = false,
    @JsonProperty("streams") val streams: List<TsStream> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TsSubCategory(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TsGenre(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("sub_categories") val subCategories: List<TsSubCategory> = emptyList(),
)

/** `GET /api/live-upcoming` */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TsEvents(
    @JsonProperty("events") val events: List<TsItem> = emptyList(),
    @JsonProperty("genres") val genres: List<TsGenre> = emptyList(),
)

/** `GET /api/channels` */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TsChannels(
    @JsonProperty("channels") val channels: List<TsItem> = emptyList(),
    @JsonProperty("genres") val genres: List<TsGenre> = emptyList(),
)

/** `GET /api/replays` */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TsReplays(
    @JsonProperty("replays") val replays: List<TsItem> = emptyList(),
)

/** `GET /api/watch/<slug>` */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TsWatch(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("genre_name") val genreName: String? = null,
    @JsonProperty("item") val item: TsItem? = null,
)

/** A source stored in a result's `data`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TsLink(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("url") val url: String = "",
)
