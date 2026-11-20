package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Values taken from responses saved on 2026-11-20 (recon folder samples/, copied to src/test/resources). */
class TimStreamsParserTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

    private val events get() = TimStreamsParser.events(res("api_live-upcoming.json"))!!
    private val channels get() = TimStreamsParser.channels(res("api_channels.json"))!!

    @Test fun eventsParse() {
        assertEquals(23, events.events.size)
        val first = events.events.first()
        assertEquals("buffalo-sabres-v-washington-capitals-401892746", first.url)
        assertEquals("11", first.genre)
        assertEquals("ESPN+", first.streams.single().name)
        assertEquals("https://ehgembxe.cfd/mpcx76jc-5929", first.streams.single().url)
    }

    @Test fun eventRowsGroupByGenreInTableOrder() {
        val rows = TimStreamsParser.eventRows(events)
        val names = rows.map { it.first }
        // Genre table order: Soccer(1), Motorsport(2), Basketball(7), American Football(8), Hockey(11)
        assertEquals(listOf("Soccer", "Motorsport", "Basketball", "American Football", "Hockey"), names)
        assertEquals(23, rows.sumOf { it.second.size })
        assertEquals(12, rows.first { it.first == "Hockey" }.second.size)
        // Within a genre the site's time order is kept.
        assertEquals("buffalo-sabres-v-washington-capitals-401892746", rows.first { it.first == "Hockey" }.second.first().url)
    }

    @Test fun placeholderGenresBecomeOther() {
        val genres = listOf(TsGenre("4", "TBA"), TsGenre("5", "TBA"), TsGenre("16", "Rugby"), TsGenre("15", "Rugby"))
        assertEquals("Other", TimStreamsParser.genreName(genres, "4"))
        assertEquals("Other", TimStreamsParser.genreName(genres, "99"))
        val item = { id: String, genre: String ->
            TsItem(url = id, name = id, genre = genre, streams = listOf(TsStream("s", "https://ehgembxe.cfd/abcdefgh-1234")))
        }
        val rows = TimStreamsParser.eventRows(TsEvents(listOf(item("a", "15"), item("b", "16"), item("c", "5")), genres))
        // Rugby twice in the table merges into one row, TBA into "Other", in table order.
        assertEquals(listOf("Other", "Rugby"), rows.map { it.first })
        assertEquals(2, rows.first { it.first == "Rugby" }.second.size)
    }

    @Test fun channelRowsKeepFreeStreamsOfPartlyVipChannels() {
        val rows = TimStreamsParser.channelRows(channels)
        assertEquals(
            listOf("Live TV - Entertainment", "Live TV - Sports", "Live TV - Cartoons", "Live TV - Movies/Cinema", "Live TV - News/Politics"),
            rows.map { it.first }
        )
        val all = rows.flatMap { it.second }
        assertEquals(226, all.size)
        assertTrue(all.any { it.url == "abc" })
        // Three channels also carry a "4K HEVC" VIP stream: the channel stays, only that stream goes.
        val f1 = TimStreamsParser.linksOf(all.first { it.url == "sky-sports-f1" })
        assertEquals(listOf("English", "Italian", "German"), f1.map { it.name })
    }

    @Test fun vipStreamsAreDropped() {
        val item = TsItem(
            url = "x", name = "X",
            streams = listOf(TsStream("A", "https://ehgembxe.cfd/aaaaaaaa-1111", vip = true), TsStream("B", "https://ehgembxe.cfd/bbbbbbbb-2222"))
        )
        assertEquals(listOf("https://ehgembxe.cfd/bbbbbbbb-2222"), TimStreamsParser.playable(item).map { it.url })
        assertTrue(TimStreamsParser.playable(item.copy(vip = true)).isEmpty())
        assertFalse(TimStreamsParser.isListed(item.copy(streams = listOf(TsStream("A", "https://e/x", vip = true)))))
    }

    @Test fun postersAreAbsolute() {
        val main = "https://tim-streams.com"
        assertEquals("https://tim-streams.com/img/nhl-401892746.png", TimStreamsParser.posterUrl("/img/nhl-401892746.png", main))
        assertEquals("https://cdn.abcotvs.com/a.jpg", TimStreamsParser.posterUrl("https://cdn.abcotvs.com/a.jpg", main))
        assertEquals("https://x.example/a.png", TimStreamsParser.posterUrl("//x.example/a.png", main))
        assertNull(TimStreamsParser.posterUrl("  ", main))
        assertNull(TimStreamsParser.posterUrl(null, main))
    }

    @Test fun pageUrlsRoundTrip() {
        val url = TimStreamsParser.pageUrl("https://tim-streams.com/", TimStreamsParser.KIND_EVENT, "colts-v-texans-401")
        assertEquals("https://tim-streams.com/watch/colts-v-texans-401", url)
        assertEquals("watch" to "colts-v-texans-401", TimStreamsParser.pageRef(url))
        assertEquals("channel" to "abc", TimStreamsParser.pageRef("https://tim-streams.com/channel/abc?x=1"))
        assertEquals("replay" to "prime-show-mma-19", TimStreamsParser.pageRef("https://tim-streams.com/replay/prime-show-mma-19"))
        assertNull(TimStreamsParser.pageRef("https://tim-streams.com/streams"))
    }

    @Test fun titlesCarryTheClock() {
        assertEquals("19:00  Buffalo Sabres @ Washington Capitals", TimStreamsParser.eventTitle(events.events.first()))
        assertEquals("2026-11-19 19:00", TimStreamsParser.dateTime("2026-11-19T19:00"))
        assertNull(TimStreamsParser.clock("tomorrow"))
        assertEquals("Soccer-less", TimStreamsParser.eventTitle(TsItem(name = "Soccer-less", time = null)))
        val hockey = events.genres.first { it.id == "11" }
        assertEquals("Hockey", hockey.name)
        assertEquals("National Hockey League", TimStreamsParser.subGenreName(events.genres, "11", "1"))
    }

    @Test fun searchMatchesEveryWord() {
        val first = events.events.first()
        assertTrue(TimStreamsParser.matches(first, "Hockey", "sabres capitals"))
        assertTrue(TimStreamsParser.matches(first, "Hockey", "HOCKEY buffalo"))
        assertFalse(TimStreamsParser.matches(first, "Hockey", "sabres rangers"))
        assertFalse(TimStreamsParser.matches(first, "Hockey", "  "))
    }

    @Test fun replaysAndWatchParse() {
        val replay = TimStreamsParser.replays(res("api_replays.json"))!!.replays.single()
        assertEquals("prime-show-mma-19", replay.url)
        assertEquals("11/07/2026", replay.date)
        assertEquals("https://voe.sx/e/r3vhzvpxehk6", replay.streams.single().url)
        assertFalse(TimStreamsParser.isPlayerEmbed(replay.streams.single().url!!))
        val watch = TimStreamsParser.watch(res("api_watch_event.json"))!!
        assertEquals("Hockey", watch.genreName)
        assertEquals("event", watch.type)
        assertEquals("buffalo-sabres-v-washington-capitals-401892746", watch.item!!.url)
        assertNull(TimStreamsParser.watch("""{"error":"Not found","success":false}"""))
        assertNull(TimStreamsParser.events("<html>"))
    }

    @Test fun dataRoundTrips() {
        val links = TimStreamsParser.linksOf(events.events.first())
        assertEquals(listOf(TsLink("ESPN+", "https://ehgembxe.cfd/mpcx76jc-5929")), links)
        assertEquals(links, TimStreamsParser.links(TimStreamsParser.linksJson(links)))
        assertTrue(TimStreamsParser.links("not json").isEmpty())
        assertTrue(TimStreamsParser.links("""[{"name":"x","url":"relative/path"}]""").isEmpty())
    }

    @Test fun playerEmbedsNeedABrowser() {
        assertTrue(TimStreamsParser.isPlayerEmbed("https://ehgembxe.cfd/mpcx76jc-5929"))
        // The embed host rotates; the path shape still says "player page".
        assertTrue(TimStreamsParser.isPlayerEmbed("https://other-host.example/nhmzkzez-3028"))
        assertFalse(TimStreamsParser.isPlayerEmbed("https://voe.sx/e/r3vhzvpxehk6"))
    }

    // ---- playlists ----------------------------------------------------------------------------------------------

    private val abcMasterUrl = "https://tims-001.sytes.net/main/secure/e58479f4/1795154266/abc-usa.m3u8"

    @Test fun masterVariantResolvesAgainstTheHost() {
        val variant = TimStreamsParser.bestVariant(res("abc_master.m3u8"), abcMasterUrl)!!
        assertEquals(
            "https://tims-001.sytes.net/main/secure/f5d5b33b7a15f7d1fd86442b72934925c3bfeaff212c45812294e33afe1e98ea/1795154266/abc-usa_v0.m3u8",
            variant.url
        )
        // The site writes a bare RESOLUTION=720 on ABC, WxH on NHL.
        assertEquals(720, variant.height)
        assertEquals(7011815L, variant.bandwidth)
        val nhl = TimStreamsParser.bestVariant(res("nhl_master.m3u8"), abcMasterUrl)!!
        assertEquals(1080, nhl.height)
        assertTrue(nhl.url.startsWith("https://tims-001.sytes.net/main/secure/"))
        assertNull(TimStreamsParser.bestVariant(res("abc_media.m3u8"), abcMasterUrl))
    }

    @Test fun sniffPatternMatchesMasterAndVariant() {
        assertTrue(TimStreamsParser.MASTER_SNIFF.containsMatchIn(abcMasterUrl))
        assertTrue(TimStreamsParser.MASTER_SNIFF.containsMatchIn("https://tims-002.sytes.net/main/secure/aa/1/nhl-401892746.m3u8"))
        assertFalse(TimStreamsParser.MASTER_SNIFF.containsMatchIn("https://tims-001.sytes.net/p/0123456789abcdef"))
        assertFalse(TimStreamsParser.MASTER_SNIFF.containsMatchIn("https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js"))
    }

    @Test fun mediaPlaylistSegmentsGoThroughTheProxy() {
        val media = res("abc_media.m3u8")
        val variantUrl = "https://tims-001.sytes.net/main/secure/f5/1795154266/abc-usa_v0.m3u8"
        val out = TimStreamsParser.rewriteMedia(media, variantUrl, "http://127.0.0.1:4000/s.ts")
        val lines = out.lines()
        assertEquals("#EXT-X-MEDIA-SEQUENCE:12967", lines.first { it.startsWith("#EXT-X-MEDIA-SEQUENCE") })
        assertEquals("#EXT-X-TARGETDURATION:4", lines.first { it.startsWith("#EXT-X-TARGETDURATION") })
        val segments = lines.filter { !it.startsWith("#") && it.isNotBlank() }
        assertEquals(4, segments.size)
        assertTrue(segments.all { it.startsWith("http://127.0.0.1:4000/s.ts?u=https%3A%2F%2Fp16-common-sign.tiktokcdn-us.com%2F") })
        // The original URL, signature included, survives the round trip.
        val original = TimStreamsParser.segmentUrls(media, variantUrl)
        assertEquals(original, segments.map { TimStreamsParser.queryU(it)!! })
        assertTrue(original.first().contains("x-signature=zKFbdoNA56SI8Ve6IDhaxMPIslk%3D&t=4d5b0474"))
        assertEquals(4, lines.count { it.startsWith("#EXTINF") })
    }

    @Test fun relativeSegmentsResolveAgainstThePlaylist() {
        val out = TimStreamsParser.segmentUrls("#EXTM3U\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\n/x/seg2.ts\n", "https://h.example/a/b/list.m3u8")
        assertEquals(listOf("https://h.example/a/b/seg1.ts", "https://h.example/x/seg2.ts"), out)
    }

    // ---- PNG unwrapping -----------------------------------------------------------------------------------------

    @Test fun realSegmentHeadUnfiltersToTheReferenceBytes() {
        // First 70 rows of a real segment: all five PNG filter types occur in them.
        // SHA-256 of the decoded pixels comes from PIL (png_segment_to_ts.py's decoder).
        val png = javaClass.classLoader!!.getResourceAsStream("segment_head.png")!!.readBytes()
        val pixels = TimStreamsSegment.pixels(png)!!
        assertEquals(742 * 70 * 3, pixels.size)
        assertEquals("577fc4b8140f350fee3ead64a4b747b7de6451916510c0a69583ba33b4cedf81", sha256(pixels))
        assertEquals("KWP1", String(pixels, 0, 4))
        assertEquals(1648196, ((pixels[4].toInt() and 0xFF) shl 24) or ((pixels[5].toInt() and 0xFF) shl 16) or
            ((pixels[6].toInt() and 0xFF) shl 8) or (pixels[7].toInt() and 0xFF))
        assertEquals(0x47, pixels[8].toInt())
        // The image is cut short, so it does not hold the whole stream it announces.
        assertNull(TimStreamsSegment.unwrap(png))
    }

    @Test fun syntheticSegmentUnwraps() {
        val ts = ByteArray(188 * 40) { if (it % 188 == 0) 0x47 else (it * 31 % 251).toByte() }
        for (channels in listOf(3, 4)) {
            val png = wrapAsPng(ts, width = 101, channels = channels)
            assertTrue(ts.contentEquals(TimStreamsSegment.unwrap(png)!!))
        }
    }

    @Test fun notASegmentIsRejected() {
        assertNull(TimStreamsSegment.unwrap("<html>403</html>".toByteArray()))
        assertNull(TimStreamsSegment.unwrap(ByteArray(0)))
        val plain = wrapAsPng(ByteArray(188) { 0x11 }, width = 32, channels = 3, magic = "NOPE")
        assertNull(TimStreamsSegment.unwrap(plain))
        val notTs = wrapAsPng(ByteArray(188) { 0x11 }, width = 32, channels = 3)
        assertNull(TimStreamsSegment.unwrap(notTs))
        assertNotNull(TimStreamsSegment.pixels(plain))
    }

    private fun sha256(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A PNG whose pixels are KWP1 + length + [ts], padded to whole rows, rows filtered with types 0-4 in turn. */
    private fun wrapAsPng(ts: ByteArray, width: Int, channels: Int, magic: String = "KWP1"): ByteArray {
        val payload = magic.toByteArray() + java.nio.ByteBuffer.allocate(4).putInt(ts.size).array() + ts
        val perRow = width * 3
        val rows = (payload.size + perRow - 1) / perRow
        val rgb = payload.copyOf(rows * perRow)
        val stride = width * channels
        val pixels = ByteArray(stride * rows)
        for (y in 0 until rows) for (x in 0 until width) {
            for (k in 0 until 3) pixels[y * stride + x * channels + k] = rgb[y * perRow + x * 3 + k]
            if (channels == 4) pixels[y * stride + x * channels + 3] = 0xFF.toByte()
        }
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until rows) {
            val type = y % 5
            raw.write(type)
            for (x in 0 until stride) {
                val v = pixels[y * stride + x].toInt() and 0xFF
                val a = if (x >= channels) pixels[y * stride + x - channels].toInt() and 0xFF else 0
                val b = if (y > 0) pixels[(y - 1) * stride + x].toInt() and 0xFF else 0
                val c = if (y > 0 && x >= channels) pixels[(y - 1) * stride + x - channels].toInt() and 0xFF else 0
                val p = a + b - c
                val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                val paeth = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                val predicted = when (type) { 0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2; else -> paeth }
                raw.write((v - predicted) and 0xFF)
            }
        }
        val deflater = java.util.zip.Deflater()
        deflater.setInput(raw.toByteArray()); deflater.finish()
        val z = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(rows).put(8).put(if (channels == 3) 2 else 6).put(0).put(0).put(0).array()
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, body: ByteArray) {
            out.write(java.nio.ByteBuffer.allocate(4).putInt(body.size).array())
            out.write(type.toByteArray()); out.write(body)
            val crc = java.util.zip.CRC32().apply { update(type.toByteArray()); update(body) }
            out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        chunk("IHDR", ihdr)
        // Two IDAT chunks, as the real files have many.
        val zb = z.toByteArray()
        chunk("IDAT", zb.copyOfRange(0, zb.size / 2)); chunk("IDAT", zb.copyOfRange(zb.size / 2, zb.size))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
