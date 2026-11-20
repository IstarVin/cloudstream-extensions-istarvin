package com.nropvox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Values from api.ppv.st/api/streams saved on 2026-11-20 (src/test/resources/streams.json, server timestamp 1795144700). */
class PPVstParserTest {
    private val json = javaClass.classLoader!!.getResource("streams.json")!!.readText()
    private val envelope get() = PPVstParser.parse(json)!!
    private val entries get() = PPVstParser.entries(envelope)
    private val now = 1795144700L
    private val manila = TimeZone.getTimeZone("Asia/Manila")

    private fun byName(name: String) = entries.first { it.stream.name == name }

    @Test fun listParsesIntoEveryStreamOnce() {
        assertEquals(1795144700L, envelope.timestamp)
        assertEquals(13, envelope.streams.size)
        assertEquals(173, entries.size)
    }

    @Test fun errorBodiesAreRejected() {
        assertNull(PPVstParser.parse("""{"success":false,"error":"API endpoint not found."}"""))
        assertNull(PPVstParser.parse("not json"))
    }

    @Test fun liveEventsAreSortedByViewers() {
        val live = PPVstParser.live(entries, now)
        assertEquals(16, live.size)
        assertEquals(live.map { PPVstParser.viewers(it.stream) }.sortedDescending(), live.map { PPVstParser.viewers(it.stream) })
        assertTrue(live.none { PPVstParser.isAlwaysLive(it.stream) })
        assertTrue(live.all { PPVstParser.isLive(it.stream, now) })
        assertEquals("Indianapolis Colts at Houston Texans", live.first { it.stream.id == 32263L }.stream.name)
    }

    @Test fun upcomingIsLaterThanNowAndSorted() {
        val upcoming = PPVstParser.upcoming(entries, now)
        assertTrue(upcoming.isNotEmpty())
        assertTrue(upcoming.all { it.stream.startsAt!! > now })
        assertEquals(upcoming.map { it.stream.startsAt }.sortedBy { it }, upcoming.map { it.stream.startsAt })
        // Live and upcoming never overlap, and neither holds 24/7 channels.
        val liveIds = PPVstParser.live(entries, now).map { it.stream.id }.toSet()
        assertTrue(upcoming.none { it.stream.id in liveIds })
        assertTrue(upcoming.none { PPVstParser.isAlwaysLive(it.stream) })
    }

    @Test fun eventBoundariesAreInclusive() {
        val s = PpvStream(id = 1, name = "x", uriName = "x", startsAt = 100, endsAt = 200, alwaysLive = 0)
        assertFalse(PPVstParser.isLive(s, 99))
        assertTrue(PPVstParser.isLive(s, 100))
        assertTrue(PPVstParser.isLive(s, 200))
        assertFalse(PPVstParser.isLive(s, 201))
        assertTrue(PPVstParser.isUpcoming(s, 99))
        assertFalse(PPVstParser.isUpcoming(s, 100))
    }

    @Test fun alwaysLiveChannelsIncludeOnesListedUnderSports() {
        val channels = PPVstParser.alwaysLive(entries)
        assertEquals(18, channels.size)
        assertTrue(channels.any { it.stream.name == "NFL Network" && it.category == "American Football" })
        assertTrue(channels.any { it.stream.name == "CNN" && it.category == "24/7 Streams" })
        // 24/7 channels with a stray start time are still never "live now" or "upcoming".
        val skySportsTennis = byName("Sky Sports Tennis").stream
        assertFalse(PPVstParser.isLive(skySportsTennis, now))
        assertFalse(PPVstParser.isUpcoming(skySportsTennis, now))
    }

    @Test fun viewersAndAlwaysLiveReadStringsAndNumbers() {
        assertEquals(4372, PPVstParser.viewers(PpvStream(viewers = "4372")))
        assertEquals(4132, PPVstParser.viewers(PpvStream(viewers = 4132)))
        assertEquals(0, PPVstParser.viewers(PpvStream(viewers = "n/a")))
        assertTrue(PPVstParser.isAlwaysLive(PpvStream(alwaysLive = true)))
        assertTrue(PPVstParser.isAlwaysLive(PpvStream(alwaysLive = "1")))
        assertFalse(PPVstParser.isAlwaysLive(PpvStream(alwaysLive = 0)))
    }

    @Test fun categoryRowsKeepServerOrder() {
        val ids = envelope.streams.first { it.category == "Basketball" }.streams.mapNotNull { it.id }
        assertEquals(ids, PPVstParser.inCategory(entries, "basketball").map { it.stream.id })
        assertEquals(24, ids.size)
    }

    @Test fun unknownCategoriesLandInMoreSports() {
        val known = listOf("American Football", "Basketball", "Football", "Ice Hockey", "Motorsports", "Wrestling", "Tennis",
            "Cricket", "Rugby", "Australian Football", "Darts", "Golf", "24/7 Streams")
        assertTrue(PPVstParser.otherCategories(entries, known).isEmpty())
        assertEquals(79, PPVstParser.otherCategories(entries, known - "Football").size)
    }

    @Test fun searchMatchesNameTagAndCategory() {
        assertTrue(PPVstParser.search(entries, "colts").any { it.stream.id == 32263L })
        assertTrue(PPVstParser.search(entries, "HOUSTON texans").any { it.stream.id == 32263L })
        assertTrue(PPVstParser.search(entries, "nfl").size >= 2)
        assertEquals(PPVstParser.inCategory(entries, "Darts").size, PPVstParser.search(entries, "darts").count { it.category == "Darts" })
        assertTrue(PPVstParser.search(entries, "   ").isEmpty())
        assertTrue(PPVstParser.search(entries, "zzzzzz-no-such-event").isEmpty())
    }

    @Test fun pageUrlsRoundTripSlashedUris() {
        assertEquals("https://ppv.st/live/nfl/2026-11-19/ind-hou", PPVstParser.pageUrl("nfl/2026-11-19/ind-hou"))
        assertEquals("nfl/2026-11-19/ind-hou", PPVstParser.uriOf("https://ppv.st/live/nfl/2026-11-19/ind-hou"))
        assertEquals("nfl-network", PPVstParser.uriOf("https://ppv.st/live/nfl-network/?x=1"))
        assertNull(PPVstParser.uriOf("https://ppv.st/"))
    }

    @Test fun timeLabelsUseLocalZoneAndDay() {
        // 1795137300 = Fri 2026-11-20 09:15 in Manila; "now" (11:18) is the same Manila day.
        assertEquals("09:15", PPVstParser.timeLabel(1795137300L, now, manila))
        // A start on another Manila day carries the weekday (+2 days = Sunday).
        assertEquals("Sun 09:15", PPVstParser.timeLabel(1795137300L + 2 * 86400, now, manila))
        val upcoming = PpvStream(id = 1, name = "A vs. B", uriName = "a", startsAt = now + 3600, endsAt = now + 20000, alwaysLive = 0)
        assertTrue(PPVstParser.title(upcoming, now, manila).endsWith("  A vs. B"))
        assertEquals("A vs. B", PPVstParser.title(upcoming.copy(startsAt = now - 10), now, manila))
    }

    @Test fun plotSummarisesStatus() {
        val colts = byName("Indianapolis Colts at Houston Texans")
        val plot = PPVstParser.plot(colts, now, manila)
        assertEquals("NFL · American Football · Live now · HD | Prime Video · 4372 viewers", plot)
        assertTrue(PPVstParser.plot(byName("NFL Network"), now, manila).contains("24/7 channel"))
    }

    @Test fun embedsListMainFeedFirstWithFullUrls() {
        val embeds = PPVstParser.embeds(byName("Indianapolis Colts at Houston Texans").stream)
        assertEquals(
            listOf(
                PpvEmbed("HD | Prime Video", "https://taifood-blog.asia/embed/nfl/2026-11-19/ind-hou"),
                PpvEmbed("SD | Prime Video", "https://taifood-blog.asia/embed/nfl/2026-11-19/ind-hou_540p"),
            ),
            embeds
        )
        assertTrue(entries.all { e -> PPVstParser.embeds(e.stream).all { it.url.startsWith("https://") } })
        val many = entries.maxByOrNull { PPVstParser.embeds(it.stream).size }!!
        assertTrue(PPVstParser.embeds(many.stream).size > 5)
        assertEquals(PPVstParser.embeds(many.stream).size, PPVstParser.embeds(many.stream).map { it.url }.toSet().size)
    }

    @Test fun embedsFillMissingLabelsAndSkipBadUrls() {
        val s = PpvStream(
            sourceTag = "", iframe = "https://h/embed/a",
            substreams = listOf(PpvSubstream(sourceTag = "SD | X", iframe = "/embed/b"), PpvSubstream(sourceTag = "SD | Y", iframe = "https://h/embed/a"),
                PpvSubstream(sourceTag = null, iframe = "https://h/embed/c"))
        )
        assertEquals(listOf(PpvEmbed("Feed 1", "https://h/embed/a"), PpvEmbed("Feed 2", "https://h/embed/c")), PPVstParser.embeds(s))
    }

    @Test fun dataJsonRoundTrips() {
        val colts = byName("Indianapolis Colts at Houston Texans").stream
        val data = PPVstParser.data(PPVstParser.dataJson(colts))
        assertNotNull(data)
        assertEquals("nfl/2026-11-19/ind-hou", data!!.uri)
        assertEquals(PPVstParser.embeds(colts), data.embeds)
        assertNull(PPVstParser.data("""{"uri":"x","embeds":[]}"""))
        assertNull(PPVstParser.data("https://ppv.st/live/x"))
    }

    @Test fun qualityFromLabel() {
        assertEquals(720, PPVstParser.quality("HD | ABC"))
        assertEquals(540, PPVstParser.quality("SD | SkyCast"))
        assertEquals(0, PPVstParser.quality("Feed 1"))
    }

    @Test fun playlistRegexMatchesOnlyTheSecureM3u8() {
        val url = "https://netanyahu.indianservers.st/secure/9f1c2e5d7a8b4c3d2e1f0a9b8c7d6e5f/1795126500/1795158900/houstontexans_fox/index.m3u8"
        assertTrue(PPVstParser.isPlaylistUrl(url))
        assertTrue(PPVstParser.isPlaylistUrl("$url?x=1"))
        assertFalse(PPVstParser.isPlaylistUrl("https://netanyahu.indianservers.st/m/abc.ts"))
        assertFalse(PPVstParser.isPlaylistUrl("https://taifood-blog.asia/embed/nfl/2026-11-19/ind-hou"))
    }

    @Test fun adHostsAreRecognised() {
        assertTrue(PPVstParser.isAdUrl("https://adexchangeclear.com/x.js"))
        assertFalse(PPVstParser.isAdUrl("https://taifood-blog.asia/js/bundle-jw.js"))
        assertFalse(PPVstParser.isAdUrl("https://netanyahu.indianservers.st/secure/t/0/1/k/index.m3u8"))
    }

    private fun ts(packets: Int): ByteArray =
        ByteArray(188 * packets) { i -> if (i % 188 == 0) 0x47 else (i % 251).toByte() }

    private fun webpHeader(): ByteArray {
        // 33 bytes: "RIFF" + size + "WEBPVP8 " + payload with a stray 0x47 ("G") that is not a packet start.
        val h = ByteArray(33) { (0x20 + it).toByte() }
        "RIFF".toByteArray().copyInto(h, 0)
        "WEBPVP8 ".toByteArray().copyInto(h, 8)
        h[20] = 0x47
        return h
    }

    @Test fun webpPrefixIsStrippedFromSegments() {
        val body = ts(6)
        val wrapped = webpHeader() + body
        assertEquals(33, PPVstParser.tsOffset(wrapped))
        assertArrayEquals(body, PPVstParser.unwrapSegment(wrapped))
    }

    @Test fun prefixLengthIsNotHardcoded() {
        val body = ts(5)
        val wrapped = ByteArray(57) { 0x11 } + body
        assertEquals(57, PPVstParser.tsOffset(wrapped))
        assertArrayEquals(body, PPVstParser.unwrapSegment(wrapped))
    }

    @Test fun plainTsAndUnknownDataPassThrough() {
        val body = ts(4)
        assertEquals(0, PPVstParser.tsOffset(body))
        assertArrayEquals(body, PPVstParser.unwrapSegment(body))
        val junk = ByteArray(1000) { 0x11 }
        assertEquals(-1, PPVstParser.tsOffset(junk))
        assertArrayEquals(junk, PPVstParser.unwrapSegment(junk))
        assertArrayEquals(ByteArray(0), PPVstParser.unwrapSegment(ByteArray(0)))
    }

    @Test fun recordedPlaylistUsesAbsolutePathSegments() {
        val media = javaClass.classLoader!!.getResource("media.m3u8")!!.readText()
        val segments = media.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(segments.isNotEmpty())
        assertTrue(segments.all { it.startsWith("/m/") && it.endsWith(".ts") })
        val master = javaClass.classLoader!!.getResource("master-event.m3u8")!!.readText()
        assertTrue(master.contains("RESOLUTION=1280x720"))
        assertTrue(master.lines().any { it == "tracks-v1a1/mono.ts.m3u8" })
    }
}
