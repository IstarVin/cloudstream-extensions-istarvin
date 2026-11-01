package com.nropvox

import org.junit.Assert.*
import org.junit.Test

class MkvDramaParserTest {
    private val base = "https://mkvdrama.net"
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/mkvdrama/$name.html")).readText()

    @Test fun catalogAndSearchUseTheSamePublicCardsAndNextPageLink() {
        val catalog = MkvDramaParser.catalog(fixture("catalog"), "$base/titles/?type=drama&page=1")
        assertEquals(2, catalog.items.size)
        assertEquals("Khemjira Forever", catalog.items.first().title)
        assertEquals("$base/khemjira-forever-kezqbv65", catalog.items.first().url)
        assertFalse(catalog.items.first().movie)
        assertTrue(catalog.items.first().poster!!.startsWith("https://i2.wp.com/"))
        assertTrue(catalog.hasNext)
        val movies = MkvDramaParser.catalog(fixture("movies-page-2"), "$base/titles/?type=movie&page=2")
        assertTrue(movies.items.all { it.movie })
        assertTrue(movies.hasNext)
        val search = MkvDramaParser.catalog(fixture("search"), "$base/search?q=genius&page=1")
        assertEquals("Bad Genius: The Series (PH)", search.items.first().title)
        assertTrue(search.hasNext)
    }

    @Test fun cardsHandleLazyImagesDuplicatesAndEmptyLastPages() {
        val html = """<div class="listupd"><article class="bs">
            <a class="tip" href="/movie"><h2>Movie &amp; title</h2><div class="typez">Movie</div>
            <img data-src="/poster.jpg" src="data:image/png;base64,AA"></a>
            <a class="tip" href="/movie" title="Duplicate"></a>
            <a class="tip" href="https://ads.example/title" title="Ad"></a>
            <a class="tip" href="javascript:alert(1)" title="Bad"></a>
            <a class="tip" href="/missing-title"></a></article></div>"""
        val page = MkvDramaParser.catalog(html, "$base/titles/")
        assertEquals(listOf(MkvTitleCard("Movie & title", "$base/movie", true, "$base/poster.jpg")), page.items)
        assertFalse(page.hasNext)
        val empty = MkvDramaParser.catalog("<div class=listupd>No results</div>", "$base/search?q=absent")
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasNext)
        assertThrows(IllegalStateException::class.java) { MkvDramaParser.catalog("<title>Just a moment...</title>", base) }
    }

    @Test fun movieMetadataComesFromVisibleFieldsRatherThanPostDates() {
        val movie = MkvDramaParser.details(fixture("movie"), "$base/bad-genius")
        assertEquals("Bad Genius", movie.title)
        assertTrue(movie.movie)
        assertEquals(2017, movie.year)
        assertEquals(130, movie.duration)
        assertEquals(8.6, movie.rating!!, 0.001)
        assertEquals("completed", movie.status)
        assertEquals(listOf("Thriller", "Crime", "Comedy", "Drama"), movie.genres)
        assertEquals(6, movie.actors.size)
        assertEquals("Best Vittawin Veeravidhayanant", movie.actors.first())
        assertTrue(movie.plot!!.startsWith("Lynn, a genius high school student"))
        assertTrue(movie.poster!!.contains("1683365630-6677-p67Zn_4f.jpg"))
    }

    @Test fun ongoingDramaUsesAiredYearAndLeavesMissingRatingAndCastAbsent() {
        val drama = MkvDramaParser.details(fixture("drama"), "$base/khemjira-forever-kezqbv65")
        assertEquals("Khemjira Forever", drama.title)
        assertFalse(drama.movie)
        assertEquals(2026, drama.year)
        assertEquals(60, drama.duration)
        assertEquals("ongoing", drama.status)
        assertEquals(listOf("Romance", "Supernatural", "LGBTQ+"), drama.genres)
        assertTrue(drama.actors.isEmpty())
        assertNull(drama.rating)
        assertTrue(drama.plot!!.contains("For Jettana and Chanwit"))
    }

    @Test fun challengeAndMissingOptionalMetadataDoNotBecomeInventedDetails() {
        assertThrows(IllegalStateException::class.java) { MkvDramaParser.details("<h1>Just a moment...</h1>", base) }
        val minimal = MkvDramaParser.details("<div class=bigcontent><h1 class=entry-title>Title</h1></div>", base)
        assertNull(minimal.year)
        assertNull(minimal.rating)
        assertNull(minimal.duration)
        assertNull(minimal.plot)
    }
}
