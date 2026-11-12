package com.nropvox

import org.junit.Assert.*
import org.junit.Test

/** Live API and player responses captured 2026-11-12. */
class NetMirrorParserTest {
    private fun sample(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun catalogUsesZeroBasedPager() {
        val page = NetMirrorParser.catalog(sample("movies.json"))
        assertEquals(7, page.items.size)
        assertEquals("123745", NetMirrorParser.text(page.items.first(), "id"))
        assertTrue(page.hasNext)
        assertFalse(NetMirrorParser.catalog(sample("search.json")).hasNext)
    }

    @Test fun seriesPreservesMissingEpisodeAndAllSeasons() {
        val series = NetMirrorParser.catalog(sample("series-detail.json")).items.single()
        val seasons = series.path("season").toList()
        assertEquals(listOf(1, 2, 4, 5, 6, 7), NetMirrorParser.episodes(seasons.first()))
        assertEquals((1..13).toList(), NetMirrorParser.episodes(seasons[1]))
        assertEquals(61, seasons.sumOf { NetMirrorParser.episodes(it).size })
    }

    @Test fun titleUrlRetainsEpisodeData() {
        assertEquals(NetMirrorParser.TitleRef("tv", "7802", 5, 16), NetMirrorParser.reference("https://netmirror.plus/tv/7802?season=5&episode=16"))
        assertEquals(NetMirrorParser.TitleRef("movie", "123745"), NetMirrorParser.reference("https://netmirror.plus/movie/123745"))
        assertNull(NetMirrorParser.reference(""))
        assertNull(NetMirrorParser.reference("https://netmirror.plus/search/test"))
    }

    @Test fun signatureMatchesCapturedFrontendRequest() {
        assertEquals("fe70500e204d2c3cbc3de05a2818ad727ef65de3757e5196ec51eaed679d3e95", NetMirrorParser.signature("1423191", 1794448786))
        val title = NetMirrorParser.catalog(sample("movie-detail.json")).items.single()
        val path = NetMirrorParser.playerPath(title, NetMirrorParser.TitleRef("movie", "123745"), 1794448786)
        assertTrue(path.startsWith("/play/watchbox.php?id=4192178861456740576&se=0&ep=0"))
        assertTrue(path.contains("tm_id=1423191"))
        assertTrue(path.contains("nid=123745"))
        assertEquals(2026, NetMirrorParser.year(NetMirrorParser.text(title, "release_date")))
    }

    @Test fun movieQualitiesAndNamedSubtitles() {
        val player = NetMirrorParser.player(sample("watchbox.html"))
        assertEquals(listOf(2160, 1080, 480, 360), player.videos.map { it.quality })
        assertEquals(7, player.subtitles.size)
        assertTrue(player.subtitles.any { it.label == "English" })
        assertTrue(player.videos.all { it.url.contains("sign=") && it.url.startsWith("https://") })
    }

    @Test fun seriesPlayerAndEmptyResponses() {
        val player = NetMirrorParser.player(sample("series-player.html"))
        assertTrue(player.videos.isNotEmpty())
        assertTrue(player.subtitles.any { it.label == "Filipino" })
        assertTrue(NetMirrorParser.player("<h1>Server unavailable</h1>").videos.isEmpty())
    }

    @Test fun defaultSourceIgnoresCommentedExample() {
        val player = NetMirrorParser.player("""<script>
            // url: 'https://example.com/stale.mp4',
            const vid_url2 = 'https://example.com/video.mp4?sign=live';
        </script>""")
        assertEquals(listOf("https://example.com/video.mp4?sign=live"), player.videos.map { it.url })
    }

    @Test fun embedOverridesAreEncodedAndMatchTheRequestedEpisode() {
        val title = NetMirrorParser.catalog("""{"results":[{"id":"42","title":"你好","embed_json":[
            {"se":1,"ep":2,"name":"drive","size":"1 GB","url":"https://example.com/a?x=1&y=2"}
        ]}]}""").items.single()
        val ref = NetMirrorParser.TitleRef("tv", "42", 1, 2)
        val path = NetMirrorParser.playerPath(title, ref, 1234)
        assertTrue(path.startsWith("/play/drive.php?"))
        assertTrue(path.contains("url=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3D2"))
        assertTrue(path.contains("sig=${NetMirrorParser.signature("42", 1234)}"))
        assertTrue(NetMirrorParser.playerPath(title, ref.copy(episode = 1), 1234).startsWith("/play/watchbox.php?"))
    }
}
