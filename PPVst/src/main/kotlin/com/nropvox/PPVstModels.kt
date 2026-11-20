package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `GET api.ppv.st/api/streams`. `timestamp` is server time in unix seconds. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvEnvelope(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("timestamp") val timestamp: Long = 0,
    @JsonProperty("streams") val streams: List<PpvCategory> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvCategory(
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("streams") val streams: List<PpvStream> = emptyList(),
)

/**
 * One event or 24/7 channel. `startsAt`/`endsAt` are unix seconds (0 or odd values for 24/7 channels). `alwaysLive`
 * and `viewers` change type between endpoints, so they are read loosely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvStream(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("tag") val tag: String? = null,
    @JsonProperty("source_tag") val sourceTag: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("uri_name") val uriName: String? = null,
    @JsonProperty("starts_at") val startsAt: Long? = null,
    @JsonProperty("ends_at") val endsAt: Long? = null,
    @JsonProperty("always_live") val alwaysLive: Any? = null,
    @JsonProperty("category_name") val categoryName: String? = null,
    @JsonProperty("iframe") val iframe: String? = null,
    @JsonProperty("viewers") val viewers: Any? = null,
    @JsonProperty("substreams") val substreams: List<PpvSubstream> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvSubstream(
    @JsonProperty("uri_name") val uriName: String? = null,
    @JsonProperty("source_tag") val sourceTag: String? = null,
    @JsonProperty("iframe") val iframe: String? = null,
)

/** A stream with the category row it was listed under. */
data class PpvEntry(val stream: PpvStream, val category: String)

/** One playable embed page of an event: [label] is the site's `source_tag` ("HD | ABC"). */
data class PpvEmbed(
    @JsonProperty("label") val label: String = "",
    @JsonProperty("url") val url: String = "",
)

/** The `data` string handed to `loadLinks`. */
data class PpvData(
    @JsonProperty("uri") val uri: String = "",
    @JsonProperty("embeds") val embeds: List<PpvEmbed> = emptyList(),
)
