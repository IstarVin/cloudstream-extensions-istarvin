package com.nropvox

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Saved live ARC018 responses from 2026-11-12, trimmed to the relevant markup. */
class Arc018ParserTest {
    private fun fixture(name: String) = javaClass.getResource("/arc018/$name")!!.readText()
    private fun document(name: String) = Jsoup.parse(fixture(name), "https://arc018.stream/")

    @Test fun catalogCardsKeepTypeAndPortraitPoster() {
        val movies = Arc018Parser.cards(document("movies.html"))
        val series = Arc018Parser.cards(document("series-catalog.html"))
        assertEquals(3, movies.size)
        assertTrue(movies.all { !it.series && it.poster!!.endsWith(".webp") })
        assertEquals(3, series.size)
        assertTrue(series.all { it.series })
        assertTrue(Arc018Parser.hasNext(document("movies.html"), 1))
        assertFalse(Arc018Parser.hasNext(document("search.html"), 1))
    }

    @Test fun searchFindsShawshank() {
        val cards = Arc018Parser.cards(document("search.html"))
        assertEquals("The Shawshank Redemption", cards.single().title)
        assertTrue(cards.single().url.startsWith("https://arc018.stream/watch-movie/"))
    }

    @Test fun movieMetadataUsesStructuredDescription() {
        val info = Arc018Parser.details(document("movie.html"))!!
        assertEquals("The Shawshank Redemption", info.title)
        assertEquals(1994, info.year)
        assertEquals(142, info.duration)
        assertTrue(info.plot!!.startsWith("Imprisoned in the 1940s"))
        assertTrue(info.episodes.isEmpty())
        assertFalse(info.series)
        assertNotNull(Arc018Parser.playerToken(document("movie.html")))
    }

    @Test fun seriesIncludesAllSeasonsWithPaddedEpisodeUrls() {
        val info = Arc018Parser.details(document("series.html"))!!
        assertEquals(2008, info.year)
        assertTrue(info.series)
        assertEquals(62, info.episodes.size)
        assertEquals((1..5).toList(), info.episodes.map { it.season }.distinct())
        assertEquals(16, info.episodes.last().episode)
        assertEquals(Arc018Episode("https://arc018.stream/episode/show/s01-e02/?x=1", 1, 2),
            Arc018Parser.episode("https://arc018.stream/episode/show/s01-e02/?x=1"))
        assertNull(Arc018Parser.episode("https://arc018.stream/watch-series/show/"))
        assertNotNull(Arc018Parser.playerToken(document("episode.html")))
    }

    @Test fun serverListsAndEncodedSubtitleQueries() {
        for (file in listOf("movie-servers.json", "episode-servers.json")) {
            val servers = Arc018Parser.servers(fixture(file))
            assertEquals(2, servers.size)
            val first = Arc018Parser.subtitleEndpoint(servers[0].link)
            assertEquals(first, Arc018Parser.subtitleEndpoint(servers[1].link))
            assertTrue(first!!.startsWith("https://qqqcdn.cloud/subtitles/"))
        }
        val track = Arc018Parser.tracks(fixture("subtitles.json")).single()
        assertEquals("English", track.label)
        assertTrue(track.url.endsWith(".vtt"))
    }

    @Test fun vidmolySelectsHlsAndExcludesThumbnailAndSubtitleFiles() {
        val streams = Arc018Parser.vidmolyStreams(fixture("vidmoly.html"))
        assertEquals(1, streams.size)
        assertTrue(streams.single().contains("/master.m3u8?"))
        assertTrue(streams.single().contains("&e=43200"))
        assertTrue(Arc018Parser.vidmolyStreams("<html>unavailable</html>").isEmpty())
    }
}
