package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses saved from strmfree.pk on 2026-11-14 (src/test/resources). Embed pages are cut down to their script constants. */
class StrmFreeParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun utc(s: Long) = java.text.SimpleDateFormat("EEE d MMM HH:mm 'UTC'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(s * 1000))

    private val streams get() = StrmFreeParser.streams(text("streams.json"))

    @Test fun listParsesEveryStream() {
        assertEquals(20, streams.size)
        val first = streams.first()
        assertEquals("israel-vs-kosovo", first.key)
        assertEquals("Israel vs Kosovo", first.name)
        assertEquals("soccer", first.category)
        assertEquals("UEFA Nations League", first.league)
        assertEquals(1794664800L, first.timestamp)
    }

    @Test fun liveMeansHavingALiveSourceNotATimestamp() {
        val live = StrmFreeParser.live(streams)
        assertEquals(
            setOf(
                "portland-trail-blazers-vs-sacramento-kings", "oklahoma-city-thunder-vs-la-clippers",
                "illinois-fighting-illini-vs-ucla-bruins", "houston-cougars-vs-colorado-buffaloes",
                "skyf1", "skysportstennis", "willow", "skysportscricket",
            ),
            live.map { it.key }.toSet()
        )
        assertTrue(live.zipWithNext().all { (a, b) -> a.viewers >= b.viewers })
    }

    @Test fun upcomingSkipsLiveAndStaleEntriesAndSortsByStart() {
        val now = 1794633154L
        val upcoming = StrmFreeParser.upcoming(streams, now)
        assertTrue(upcoming.none { StrmFreeParser.isLive(it) })
        // redzone has no source and a start time weeks in the past
        assertFalse(upcoming.any { it.key == "redzone" })
        assertEquals("nashville-predators-vs-vegas-golden-knights", upcoming.first().key)
        assertEquals(upcoming.map { it.timestamp }.sortedBy { it }, upcoming.map { it.timestamp })
    }

    @Test fun categoryRowPutsLiveFirst() {
        val football = StrmFreeParser.inCategory(streams, "football")
        assertEquals(10, football.size)
        assertEquals(2, football.takeWhile { StrmFreeParser.isLive(it) }.size)
        assertTrue(football.drop(2).none { StrmFreeParser.isLive(it) })
    }

    @Test fun searchMatchesNameLeagueAndCategory() {
        assertEquals(1, streams.count { StrmFreeParser.matches(it, "kosovo") })
        assertTrue(streams.count { StrmFreeParser.matches(it, "nba") } >= 2)
        assertTrue(StrmFreeParser.matches(streams.first { it.key == "willow" }, "CRICKET"))
        assertFalse(StrmFreeParser.matches(streams.first(), "zzz"))
    }

    @Test fun playerUrlsRoundTrip() {
        val url = StrmFreeParser.playerUrl("https://strmfree.pk", "soccer", "ghana-vs-england")
        assertEquals("https://strmfree.pk/player/soccer/ghana-vs-england", url)
        assertEquals("soccer" to "ghana-vs-england", StrmFreeParser.playerRef("$url?t=3"))
        assertEquals("football" to "a b", StrmFreeParser.playerRef("https://strmfree.pk/player/football/a%20b"))
        assertNull(StrmFreeParser.playerRef("https://strmfree.pk/multi-stream"))
    }

    @Test fun thumbnailsAreMadeAbsolute() {
        assertEquals(
            "https://streamfree.top/thumbnails/soccer_israel-vs-kosovo_Israel_vs_Kosovo",
            StrmFreeParser.thumbnail("https://strmfree.pk", streams.first())
        )
        assertEquals(
            "https://strmfree.pk/thumbnails/x",
            StrmFreeParser.thumbnail("https://strmfree.pk", SfStream(thumbnail = "/thumbnails/x"))
        )
    }

    @Test fun singleStreamAndSourceEndpoints() {
        val one = """{"id":"x","name":"Sky Sports F1","category":"racing","league":"F1","stream_key":"skyf1","match_timestamp":1782403200,
            "sources":[{"quality":"FHD","url":"https://strmfree.st/embed/racing/skyf11080p","live":true,"broadcast":"Sky Sports F1"}]}"""
        assertEquals("skyf1", StrmFreeParser.stream(one)!!.key)
        assertNull(StrmFreeParser.stream("""{"error":"Stream not found"}"""))
        val sources = StrmFreeParser.sources("""{"stream_key":"skyf1","sources":[{"quality":"FHD","url":"https://strmfree.st/embed/racing/skyf11080p","live":true,"broadcast":"Sky Sports F1"}]}""")
        assertEquals(1, sources.size)
        assertEquals("Sky Sports F1", sources.single().broadcast)
        assertTrue(StrmFreeParser.sources("""{"stream_key":"wales-vs-norway","sources":[]}""").isEmpty())
        assertTrue(StrmFreeParser.sources("<html>").isEmpty())
    }

    @Test fun variantAndQualityOfSources() {
        assertEquals("ghana-vs-england1080p", StrmFreeParser.variantOf("https://strmfree.st/embed/soccer/ghana-vs-england1080p"))
        assertEquals("ghana-vs-england1080p2", StrmFreeParser.variantOf("https://strmfree.st/embed/soccer/ghana-vs-england1080p2?x=1"))
        assertEquals(listOf(480, 720, 1080, 2160, 480), listOf("SD", "HD", "FHD", "4K", null).map(StrmFreeParser::heightOfLabel))
        assertEquals(1080, StrmFreeParser.heightOfQuality("1080p"))
        assertEquals(0, StrmFreeParser.heightOfQuality("auto"))
    }

    @Test fun sourceLabelsNameTheBroadcaster() {
        val s = SfSource(quality = "FHD", url = "u", live = true, broadcast = "Sky Sports Main Event")
        assertEquals("StrmFree FHD Sky Sports Main Event", StrmFreeParser.sourceLabel("StrmFree", s, 0))
        assertEquals("StrmFree FHD", StrmFreeParser.sourceLabel("StrmFree", s.copy(broadcast = null), 0))
        assertEquals("StrmFree Source 2", StrmFreeParser.sourceLabel("StrmFree", SfSource(url = "u"), 1))
    }

    @Test fun liveEmbedGivesVariantAndSignedToken() {
        val embed = StrmFreeParser.embed(text("embed-live.html"))!!
        assertEquals("skyf1", embed.key)
        assertEquals("1080p", embed.quality)
        assertEquals("", embed.suffix)
        assertEquals("skyf11080p", embed.variant)
        assertTrue(embed.live)
        val token = embed.token!!
        assertEquals("RlDZj7QRosUkbTGqS6QgBQ", token.t)
        assertEquals(1794661931L, token.e)
        assertEquals("198882c21dd20871", token.n)
    }

    @Test fun upcomingEmbedIsNotLive() {
        val embed = StrmFreeParser.embed(text("embed-upcoming.html"))!!
        assertEquals("wales-vs-norway1080p", embed.variant)
        assertFalse(embed.live)
        assertNotNull(embed.token)
    }

    @Test fun otherSourcesCarryASuffix() {
        val html = text("embed-live.html").replace("const SOURCE_SUFFIX = ''", "const SOURCE_SUFFIX = '2'")
        assertEquals("skyf11080p2", StrmFreeParser.embed(html)!!.variant)
    }

    @Test fun pagesWithoutPlayerDataGiveNull() {
        assertNull(StrmFreeParser.embed("<html><body>403</body></html>"))
        val noToken = text("embed-live.html").replace("""const _0x = {""", """const _0y = {""")
        assertNull(StrmFreeParser.embed(noToken)!!.token)
        assertNull(StrmFreeParser.playlistUrl("https://strmfree.pk", "live", StrmFreeParser.embed(noToken)!!))
    }

    @Test fun streamKeyResponses() {
        val k = StrmFreeParser.streamKey(text("stream-key.json"))!!
        assertEquals("externalcdn", k.serverName)
        assertFalse(k.isExternal)
        val ext = StrmFreeParser.streamKey("""{"stream_key":"x","is_external":true,"external_url":"https://e.example/x.m3u8","server_name":"origin"}""")!!
        assertTrue(ext.isExternal)
        assertEquals("https://e.example/x.m3u8", ext.externalUrl)
        assertNull(StrmFreeParser.streamKey("<html>"))
    }

    @Test fun playlistPathFollowsTheServerNameAndKeepsTheOthersAsFallback() {
        assertEquals(listOf("live-externalcdn", "live-cdn", "live"), StrmFreeParser.playlistPrefixes("externalcdn"))
        assertEquals(listOf("live", "live-externalcdn", "live-cdn"), StrmFreeParser.playlistPrefixes("origin"))
        assertEquals(listOf("live", "live-externalcdn", "live-cdn"), StrmFreeParser.playlistPrefixes(null))
        assertEquals(listOf("live-cdn", "live-externalcdn", "live"), StrmFreeParser.playlistPrefixes("cdn2"))
    }

    @Test fun playlistUrlCarriesTheSignature() {
        val embed = StrmFreeParser.embed(text("embed-live.html"))!!
        assertEquals(
            "https://strmfree.pk/live-externalcdn/skyf11080p/index.m3u8?_t=RlDZj7QRosUkbTGqS6QgBQ&_e=1794661931&_n=198882c21dd20871",
            StrmFreeParser.playlistUrl("https://strmfree.pk", "live-externalcdn", embed)
        )
    }

    @Test fun playlistDetectionRejectsTheForbiddenPage() {
        assertTrue(StrmFreeParser.isPlaylist(text("playlist.m3u8")))
        assertFalse(StrmFreeParser.isPlaylist("<html>\n<head><title>403 Forbidden</title></head>"))
        assertFalse(StrmFreeParser.isPlaylist(""))
    }

    @Test fun plotSaysWhenAndOnWhat() {
        val now = 1794633154L
        val live = streams.first { it.key == "oklahoma-city-thunder-vs-la-clippers" }
        assertEquals("NBA · Live now · On Thunder @ Clippers - Thunder (In-Arena)", StrmFreeParser.plot(live, now))
        assertEquals("NBA · Live now", StrmFreeParser.plot(live.copy(sources = live.sources.map { it.copy(broadcast = null) }), now))
        val withBroadcast = live.copy(sources = listOf(SfSource("FHD", "u", true, "TNT Sports 1"), SfSource("HD", "v", true, "TNT Sports 1"), SfSource("SD", "w", true, "Sky Sports")))
        assertEquals("NBA · Live now · On TNT Sports 1, Sky Sports", StrmFreeParser.plot(withBroadcast, now))
        val soon = streams.first { it.key == "wales-vs-norway" }
        assertEquals("UEFA Nations League · Starts " + utc(soon.timestamp!!), StrmFreeParser.plot(soon, now))
        assertTrue(StrmFreeParser.plot(soon, soon.timestamp + 60)!!.contains("Scheduled "))
        assertNull(StrmFreeParser.plot(SfStream(), now))
    }
}
