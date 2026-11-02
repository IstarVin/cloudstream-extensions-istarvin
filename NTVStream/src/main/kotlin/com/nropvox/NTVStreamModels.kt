package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `GET ntv.st/api/get-matches`. `all` holds every match; `live` and `nonLive` split it on raptor only. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NtvMatchList(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("upstream_busy") val upstreamBusy: Boolean = false,
    @JsonProperty("all") val all: List<NtvMatch> = emptyList(),
)

/** `date` and `ends` are epoch milliseconds; `date` 0 is a 24/7 channel. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NtvMatch(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("tournament") val tournament: String? = null,
    @JsonProperty("date") val date: Long? = null,
    @JsonProperty("sources") val sources: List<NtvSource> = emptyList(),
)

/** `url` is the embed page; `channelName` is the upstream channel's label. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NtvSource(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("channelName") val channelName: String? = null,
)

/** `GET ntv.st/api/get-channels`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NtvChannelList(
    @JsonProperty("success") val success: Boolean = false,
    @JsonProperty("channels") val channels: List<NtvChannel> = emptyList(),
    @JsonProperty("has_more") val hasMore: Boolean = false,
)

/** `server` is `cdnlive` (Titan), `dlhd` (Phoenix, empty `channel_url`), `hesgoales` (Falcon) or `scorpion`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NtvChannel(
    @JsonProperty("channel_id") val id: String? = null,
    @JsonProperty("channel_name") val name: String? = null,
    @JsonProperty("channel_url") val url: String? = null,
    @JsonProperty("channel_image") val image: String? = null,
    @JsonProperty("server") val server: String? = null,
)

/** What `load` hands to `loadLinks`: one entry per playable source. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NtvLink(
    @JsonProperty("n") val name: String = "",
    @JsonProperty("u") val url: String = "",
)
