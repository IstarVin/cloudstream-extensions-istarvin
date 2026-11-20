package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Responses saved from sportsbite.org on 2026-11-20 (src/test/resources). */
class SportsBiteParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val utc = TimeZone.getTimeZone("UTC")
    private val page get() = SportsBiteParser.schedule(text("schedule.json"))!!
    private val events get() = SportsBiteParser.usable(page.events)
    private val clippers get() = events.first { it.slug == "la-clippers-vs-san-antonio-spurs" }

    @Test fun schedulePageParses() {
        assertEquals(76, events.size)
        assertEquals(1, page.pagination.pages)
        assertEquals(76, page.pagination.total)
    }

    @Test fun notJsonIsNull() {
        assertNull(SportsBiteParser.schedule("<html>nope</html>"))
        assertNull(SportsBiteParser.schedule("{\"error\":\"invalid_query\"}"))
    }

    @Test fun emptyResultIsStillASchedule() {
        val s = SportsBiteParser.schedule("""{"version":"1","pagination":{"page":1,"limit":50,"total":0,"pages":0},"events":[]}""")
        assertNotNull(s)
        assertTrue(s!!.events.isEmpty())
    }

    @Test fun liveFirstThenKickoff() {
        val ordered = SportsBiteParser.ordered(events)
        assertEquals(8, ordered.takeWhile { SportsBiteParser.isLive(it) }.size)
        val upcoming = ordered.drop(8).map { it.timestamp!! }
        assertEquals(upcoming.sorted(), upcoming)
    }

    @Test fun streamIdsComeFromEmbedUrls() {
        assertEquals(listOf("s-3ac7f69223320a9a3886", "s-4a7ae2bab18ca01144fd"), SportsBiteParser.streamIds(clippers))
        assertTrue(events.filter { it.status == "upcoming" }.all { SportsBiteParser.streamIds(it).isEmpty() })
    }

    @Test fun dataRoundTrips() {
        val d = SportsBiteParser.data(SportsBiteParser.dataJson(clippers))!!
        assertEquals(clippers.id, d.id)
        assertEquals("la-clippers-vs-san-antonio-spurs", d.slug)
        assertEquals(2, d.streams.size)
        assertNull(SportsBiteParser.data("https://sportsbite.org/watch/x"))
    }

    @Test fun watchUrlAndSlug() {
        val e = events.first()
        assertEquals("https://sportsbite.org/watch/${e.slug}", SportsBiteParser.watchUrl("https://sportsbite.org", e))
        assertEquals("a-vs-b", SportsBiteParser.slugOf("https://sportsbite.org/watch/a-vs-b/?x=1"))
        assertEquals("a-vs-b", SportsBiteParser.slugOf("a-vs-b"))
    }

    @Test fun sportLabels() {
        assertEquals("American Football", SportsBiteParser.sportLabel("american-football"))
        assertEquals("Motorsports", SportsBiteParser.sportLabel("motorsports"))
        assertEquals("Other", SportsBiteParser.sportLabel(null))
    }

    @Test fun posterPrefersHomeLogoThenTournament() {
        assertEquals("https://sportsbite.org/img/c/bf75926cfbfa11200c75ad1753c9ad105935acdb.webp", SportsBiteParser.poster(clippers))
        val f1 = events.first { it.sport == "motorsports" }
        assertEquals("https://sportsbite.org/assets/f1-logo.png", SportsBiteParser.poster(f1))
        assertNull(SportsBiteParser.poster(SbEvent(id = "x")))
    }

    @Test fun timeFormatting() {
        // 2026-11-20T01:00Z, live
        assertEquals("Fri 20 Nov 01:00", SportsBiteParser.kickoff(clippers.timestamp!!, utc))
        assertEquals("Fri 20 Nov 09:00", SportsBiteParser.kickoff(clippers.timestamp!!, TimeZone.getTimeZone("Asia/Manila")))
        assertEquals("NBA · Basketball · Live now", SportsBiteParser.plot(clippers, utc))
        val upcoming = events.first { !SportsBiteParser.isLive(it) }
        assertTrue(SportsBiteParser.plot(upcoming, utc).endsWith("UTC · No stream assigned yet"))
        assertTrue(SportsBiteParser.cardTitle(upcoming, utc).matches(Regex("""\d\d:\d\d .+""")))
        assertEquals("LA Clippers vs. San Antonio Spurs", SportsBiteParser.cardTitle(clippers, utc))
    }

    @Test fun searchMatchesTitleTeamAndTournament() {
        assertTrue(events.count { SportsBiteParser.matches(it, "clippers") } >= 1)
        assertTrue(events.count { SportsBiteParser.matches(it, "nba") } >= 4)
        assertTrue(events.any { SportsBiteParser.matches(it, "MLS") })
        assertFalse(events.any { SportsBiteParser.matches(it, "zzzzqq") })
        assertFalse(SportsBiteParser.matches(events.first(), "  "))
    }

    @Test fun variantUrlResolvesAgainstThePlayUrl() {
        assertEquals(
            "https://embed.sportsbite.org/selfhost/cdn/s-3ac7f69223320a9a3886.m3u8?cdn=1",
            SportsBiteParser.variantUrl(text("play-master.m3u8"), "https://embed.sportsbite.org/selfhost/play/s-3ac7f69223320a9a3886")
        )
        assertNull(SportsBiteParser.variantUrl("#EXTM3U\n", "https://embed.sportsbite.org/selfhost/play/x"))
    }

    @Test fun windowOfMediaPlaylists() {
        val a = SportsBiteParser.window(text("cdn-media.m3u8"))
        assertEquals(5, a.target)
        assertEquals(9 * 4.004, a.seconds, 0.001)
        val b = SportsBiteParser.window(text("cdn-media-tna.m3u8"))
        assertEquals(8, b.target)
        assertTrue(b.seconds > a.seconds)
        assertEquals(SportsBiteParser.Window(0.0, 0), SportsBiteParser.window("{\"error\":1}"))
    }

    @Test fun rankingPrefersResolutionThenLongestWindow() {
        data class S(val n: String, val h: Int, val w: Double)
        val servers = listOf(S("short", 1080, 16.0), S("low", 720, 90.0), S("long", 1080, 90.0), S("mid", 1080, 24.0))
        val ranked = SportsBiteParser.rank(servers, { it.h }, { SportsBiteParser.Window(it.w, 0) })
        assertEquals(listOf("long", "mid", "short", "low"), ranked.map { it.n })
    }

    @Test fun masterPlaylist() {
        val master = text("play-master.m3u8")
        assertTrue(SportsBiteParser.isPlaylist(master))
        assertEquals(1080, SportsBiteParser.height(master))
        assertFalse(SportsBiteParser.isPlaylist("""{"error":"channel warming","status":"retry","retry_after":2}"""))
        assertEquals(0, SportsBiteParser.height("#EXTM3U\n#EXTINF:4,\nx.ts"))
        assertEquals("https://embed.sportsbite.org/selfhost/play/s-1", SportsBiteParser.playUrl("https://embed.sportsbite.org", "s-1"))
    }
}
