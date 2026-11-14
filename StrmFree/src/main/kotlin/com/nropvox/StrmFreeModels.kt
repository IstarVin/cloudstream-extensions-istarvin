package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `GET /api/v1/streams`: everything currently listed, live or not. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SfStreamList(
    @JsonProperty("streams") val streams: List<SfStream> = emptyList(),
)

/**
 * `match_timestamp` is unix seconds. 24/7 channels (`skyf1`, `willow`…) keep an old or far-future timestamp, so
 * liveness comes from [sources], which is empty until the stream goes up.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SfStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("stream_key") val key: String? = null,
    @JsonProperty("match_timestamp") val timestamp: Long? = null,
    @JsonProperty("viewers") val viewers: Int = 0,
    @JsonProperty("thumbnail_url") val thumbnail: String? = null,
    @JsonProperty("sources") val sources: List<SfSource> = emptyList(),
)

/** `quality` is SD/HD/FHD/4K; `url` is the embed page, whose last segment is the variant (`<key>1080p2`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SfSource(
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("live") val live: Boolean = false,
    @JsonProperty("broadcast") val broadcast: String? = null,
)

/** `GET /api/v1/sources/{key}`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SfSourceList(
    @JsonProperty("sources") val sources: List<SfSource> = emptyList(),
)

/** `GET /get-stream-key/{key}`: which CDN path serves the playlist. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SfStreamKey(
    @JsonProperty("is_external") val isExternal: Boolean = false,
    @JsonProperty("external_url") val externalUrl: String? = null,
    @JsonProperty("server_name") val serverName: String? = null,
)

/** One quality's playlist signature from the embed page's `_0x` map. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SfToken(
    @JsonProperty("_t") val t: String = "",
    @JsonProperty("_e") val e: Long = 0,
    @JsonProperty("_n") val n: String = "",
)

/** What an embed page says about its variant. */
data class SfEmbed(
    val key: String,
    val quality: String,
    val suffix: String,
    val live: Boolean,
    val token: SfToken?,
) {
    val variant get() = key + quality + suffix
}
