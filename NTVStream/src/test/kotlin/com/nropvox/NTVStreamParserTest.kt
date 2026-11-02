package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses and embed pages saved from ntv.st on 2026-11-02 (src/test/resources). */
class NTVStreamParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val titan get() = NTVStreamParser.matches(text("matches-titan.json"))!!.all

    @Test fun titanMatchesKeepOnlyOnesWithCdnliveSources() {
        assertEquals(8, titan.size)
        val playable = titan.filter { NTVStreamParser.playable(it).isNotEmpty() }
        assertEquals(listOf("titan-2407200", "titan-2407201", "titan-2431516"), playable.map { it.id })
        assertEquals("Max Sport 4 FHD BG", NTVStreamParser.playable(playable.first()).first().channelName)
    }

    @Test fun raptorEmbedsAreNotSupported() {
        val raptor = NTVStreamParser.matches(text("matches-raptor.json"))!!.all
        assertEquals(3, raptor.size)
        assertTrue(raptor.all { NTVStreamParser.playable(it).isEmpty() })
    }

    @Test fun matchTiming() {
        val m = titan.first { it.id == "titan-2407200" }   // 2026-11-02T00:00:00Z
        val start = m.date!!
        assertFalse(NTVStreamParser.isLive(m, start - 20 * 60_000L))
        assertTrue(NTVStreamParser.isLive(m, start - 10 * 60_000L))
        assertTrue(NTVStreamParser.isLive(m, start + 2 * 3_600_000L))
        assertFalse(NTVStreamParser.isLive(m, start + 5 * 3_600_000L))
        assertEquals(listOf("titan-2407201"), NTVStreamParser.upcoming(titan, start + 60_000L).filter { it.id == "titan-2407201" }.map { it.id })
        assertEquals(titan.map { it.id }.toSet(), NTVStreamParser.upcoming(titan, 0).map { it.id }.toSet())
    }

    @Test fun twentyFourSevenEntriesAreNeverLiveByDate() {
        assertFalse(NTVStreamParser.isLive(NtvMatch(id = "x", date = 0), 1_000_000L))
    }

    @Test fun plot() {
        val m = NtvMatch(id = "x", category = "Football", tournament = "MLS", date = 1793583000000)
        assertEquals("MLS · Football · Mon 2 Nov 01:30 UTC", NTVStreamParser.matchPlot(m, 0))
        assertEquals("MLS · Football · Live now", NTVStreamParser.matchPlot(m, 1793583000000 + 60_000))
    }

    @Test fun titlesAreCleanedLikeTheSite() {
        assertEquals("National 100 at EAMS", NTVStreamParser.cleanTitle("🏁 National 100 at EAMS"))
        assertEquals("Inter & Roma", NTVStreamParser.cleanTitle("Inter &amp;amp; Roma"))
        assertEquals("Atlético Goianiense", NTVStreamParser.cleanTitle("Atl&#233;tico Goianiense"))
        assertEquals("- -", NTVStreamParser.cleanTitle("- -"))
    }

    @Test fun channelListFiltersUnsupportedServers() {
        val list = NTVStreamParser.channels(text("channels-p1.json"))!!
        assertEquals(50, list.channels.size)
        assertTrue(list.hasMore)
        val supported = list.channels.filter { NTVStreamParser.isSupported(it) }
        assertEquals(30, supported.size)
        assertEquals(setOf("cdnlive", "hesgoales"), supported.map { it.server }.toSet())
        assertTrue(list.channels.filter { it.server == "dlhd" }.none { NTVStreamParser.isSupported(it) })
    }

    @Test fun pageUrlsRoundTrip() {
        val url = NTVStreamParser.matchUrl("https://ntv.st", "titan", "titan-2407200")
        assertEquals("https://ntv.st/watch/titan/titan-2407200", url)
        assertEquals("titan" to "titan-2407200", NTVStreamParser.matchRef(url))
        assertNull(NTVStreamParser.matchRef("https://ntv.st/channel/titan/x"))

        val channel = NtvChannel(
            id = "cdnlive-34d0", name = "Canal 9 HD DK", server = "cdnlive",
            url = "https://cdnlivetv.tv/api/v1/channels/player/?name=Canal%209%20HD%20DK&code=&user=ntvstream&plan=free"
        )
        val cu = NTVStreamParser.channelUrl("https://ntv.st", channel)
        assertTrue(cu.startsWith("https://ntv.st/channel/cdnlive/cdnlive-34d0?u="))
        assertEquals(channel.url!! to "Canal 9 HD DK", NTVStreamParser.channelRef(cu))
        assertNull(NTVStreamParser.channelRef("https://ntv.st/watch/titan/x"))
    }

    @Test fun linksJsonRoundTrips() {
        val links = listOf(NtvLink("Max Sport 4 FHD BG", "https://cdnlivetv.tv/a?b=1&c=2"), NtvLink("x", "y"))
        assertEquals(links, NTVStreamParser.links(NTVStreamParser.linksJson(links)))
        assertEquals(emptyList<NtvLink>(), NTVStreamParser.links("https://ntv.st/watch/titan/x"))
    }

    @Test fun qualityFromLabel() {
        assertEquals(1080, NTVStreamParser.quality("Max Sport 4 FHD BG"))
        assertEquals(720, NTVStreamParser.quality("ESPN HD US"))
        assertEquals(2160, NTVStreamParser.quality("Sky Sports 4K UK"))
        assertEquals(480, NTVStreamParser.quality("Sport 2 IL"))
        assertEquals(480, NTVStreamParser.quality("HDFC"))
    }

    @Test fun cdnlivePlaylistIsAssembledFromBase64Chunks() {
        assertEquals(
            "https://cdnlivetv.tv/secure/api/v1/6add40af2d4a99e07363cefc/playlist.m3u8?token=" +
                "NmFkZDQwYWYyZDRhOTllMDczNjNjZWZjOjE3OTM2MTAxMTM3MTM6Y2RubGl2ZXR2LnR2OjIyYjQ1MmJmZTZiYzY4YWYuODNhYWJhMTlmYmU5YWM1MGU3ZDc1YWM1N2UwMDhiYjI1YjE4ODRhNTk3OThjY2EwZjFjNzBiMzI1OGE3MDc5Yw",
            NTVStreamParser.cdnlivePlaylist(text("cdnlive-espn.html"))
        )
        assertNull(NTVStreamParser.cdnlivePlaylist("<html>no player</html>"))
    }

    @Test fun wideiptvChannelFromEmbed() {
        assertEquals("beINSPORTSUS", NTVStreamParser.wideiptvChannel("https://hesgoal.team/ntvtvplayer.html?id=beINSPORTSUS"))
        assertEquals("ESPN1NL", NTVStreamParser.wideiptvChannel("https://hesgoal.team/ntvtvplayer.html?id=ESPN1NL&x=1"))
        assertEquals("abc", NTVStreamParser.wideiptvChannel("https://hesgoal.team/ntvtvplayer.html?id=a.b/c"))
        assertNull(NTVStreamParser.wideiptvChannel("https://hesgoal.team/ntvtvplayer.html"))
    }

    @Test fun wideiptvToken() {
        assertEquals(
            "YmVJTlNQT1JUU1VTfG5vX2NoZWNrX2lwfDE3OTM1OTYxNDA=.5f4a1cdef27d88c9081bd806477dcc2ba465193ed2e2b001a31a51d0e308e73c",
            NTVStreamParser.wideiptvToken(text("wideiptv-refresh.json"))
        )
        assertNull(NTVStreamParser.wideiptvToken("""{"success":false}"""))
    }

    @Test fun masterAndMediaPlaylists() {
        val master = text("wideiptv-master.m3u8")
        val variant = NTVStreamParser.bestVariant(master)
        assertTrue(variant!!.startsWith("tracks-v1a1/mono.m3u8?token="))
        assertEquals("tracks-v1a1/mono.m3u8?token=NEW.sig", NTVStreamParser.withToken(variant, "NEW.sig"))
        assertEquals("a/b.m3u8?token=T", NTVStreamParser.withToken("a/b.m3u8", "T"))
        assertNull(NTVStreamParser.bestVariant(text("wideiptv-variant.m3u8")))

        val media = NTVStreamParser.absolutize(text("wideiptv-variant.m3u8"), "https://cdn.wideiptv.top/beINSPORTSUS/tracks-v1a1/mono.m3u8")
        val segments = media.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(4, segments.size)
        assertTrue(segments.all { it.startsWith("https://cdn.wideiptv.top/beINSPORTSUS/tracks-v1a1/2026/11/02/05/") && "?token=" in it })
        assertTrue(media.startsWith("#EXTM3U\n#EXT-X-TARGETDURATION:6"))
    }

    @Test fun bestVariantPicksHighestBandwidth() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=3000000,BANDWIDTH=4000000\n\nhigh.m3u8?token=a\n"
        assertEquals("high.m3u8?token=a", NTVStreamParser.bestVariant(master))
    }

    @Test fun keyUrisAreAbsolutized() {
        val media = NTVStreamParser.absolutize("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:5,\nseg.ts\n", "https://h/a/b.m3u8")
        assertEquals("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://h/a/key.bin\"\n#EXTINF:5,\nhttps://h/a/seg.ts\n", media)
    }

    @Test fun supportedHosts() {
        assertTrue(NTVStreamParser.isSupported("https://cdnlivetv.tv/api/v1/channels/player/?name=x"))
        assertTrue(NTVStreamParser.isSupported("https://hesgoal.team/ntvtvplayer.html?id=x"))
        assertFalse(NTVStreamParser.isSupported("https://taifood-blog.asia/embed/nfl-network"))
        assertFalse(NTVStreamParser.isSupported("https://dlhd.st/stream/stream-44.php"))
        assertFalse(NTVStreamParser.isSupported("not a url"))
    }
}
