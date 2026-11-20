package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `GET sportsbite.org/api/schedule`: one page of fixtures. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SbSchedule(
    @JsonProperty("pagination") val pagination: SbPagination = SbPagination(),
    @JsonProperty("events") val events: List<SbEvent> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SbPagination(
    @JsonProperty("page") val page: Int = 1,
    @JsonProperty("pages") val pages: Int = 0,
    @JsonProperty("total") val total: Int = 0,
)

/** `status` is `live` or `upcoming`; `timestamp` is the kickoff in epoch milliseconds; `streams` is empty until assigned. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SbEvent(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("sport") val sport: String? = null,
    @JsonProperty("tournament") val tournament: SbTournament? = null,
    @JsonProperty("timestamp") val timestamp: Long? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("teams") val teams: SbTeams? = null,
    @JsonProperty("watch_url") val watchUrl: String? = null,
    @JsonProperty("streams") val streams: List<SbStream> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SbTournament(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("logo") val logo: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SbTeams(
    @JsonProperty("home") val home: SbTeam? = null,
    @JsonProperty("away") val away: SbTeam? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SbTeam(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("logo") val logo: String? = null,
)

/** `embed_url` ends in the stream id (`s-<20 hex>`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SbStream(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null,
)

/** What `load` hands to `loadLinks`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SbData(
    @JsonProperty("id") val id: String = "",
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("streams") val streams: List<String> = emptyList(),
)
