package com.nropvox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in; ordinary builds never contact the live site. Does not request media. */
class MkvDramaLiveTest {
    @Test fun catalogMetadataAndMoviePanel() = runBlocking {
        assumeTrue(System.getenv("MKVDRAMA_LIVE_TEST") == "1")
        val base = "https://mkvdrama.net"
        val session = MkvDramaSession(browserVerification = false)
        val page = session.get("$base/titles/?type=drama&order=latest&page=1")
        val catalog = MkvDramaParser.catalog(page.text, page.url)
        assertTrue(catalog.items.isNotEmpty())
        val moviePage = session.get("$base/bad-genius")
        val metadata = MkvDramaParser.details(moviePage.text, moviePage.url)
        assertTrue(metadata.movie)
        val links = MkvDramaDecoder.releases(session.panel(moviePage), "$base/bad-genius")
        assertTrue(links.isNotEmpty())
        val destinations = links.mapNotNull { session.request(it.url, "$base/bad-genius").location }
        assertTrue(destinations.any { it.startsWith("https://pixeldrain.com/u/") })
    }
}
