package com.nropvox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses saved from kotoplay.to and megaplay.buzz on 2026-11-21 (src/test/resources). */
class KotoPlayParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val mapper = jacksonObjectMapper()

    @Test fun pageUrlsGiveSlug() {
        assertEquals("naruto-shippuden-c8gov", KotoPlayParser.slugOf("https://kotoplay.to/watch/naruto-shippuden-c8gov"))
        assertEquals("naruto-shippuden-c8gov", KotoPlayParser.slugOf("https://kotoplay.to/anime/naruto-shippuden-c8gov?x=1"))
        // The app makes a bare slug absolute against mainUrl, which is not a title page.
        assertNull(KotoPlayParser.slugOf("https://kotoplay.to/naruto-shippuden-c8gov"))
    }

    @Test fun watchUrlRoundTrips() {
        val url = KotoPlayParser.watchUrl("one-piece-odmau", 135526, "dub")
        assertEquals("https://kotoplay.to/watch/one-piece-odmau?ch=135526&lang=dub", url)
        val watch = KotoPlayParser.parseWatchUrl(url)!!
        assertEquals("one-piece-odmau", watch.slug)
        assertEquals(135526, watch.chapterId)
        assertEquals("dub", watch.lang)
        assertNull(KotoPlayParser.parseWatchUrl("https://kotoplay.to/watch/one-piece-odmau"))
    }

    @Test fun serversListsThreeCdnsAndOnlyTheBareLinkIsPlain() {
        val servers = mapper.readValue<KpServers>(text("servers-27298.json")).servers!!
        assertEquals(setOf("sub", "dub"), servers.keys)
        val sub = servers["sub"]!!
        assertEquals(listOf("HD-1", "Vidstream-2", "HD-2"), sub.map { it.name })
        val plain = sub.filter { KotoPlayParser.isPlainServer(it.link!!) }
        assertEquals(listOf("https://megaplay.buzz/stream/s-2/7882/sub"), plain.map { it.link })
        assertFalse(KotoPlayParser.isPlainServer("https://megaplay.buzz/stream/s-2/7882/sub?s=tcdn"))
        assertFalse(KotoPlayParser.isPlainServer("https://megaplay.buzz/stream/s-2/7882/dub?s=bcdn"))
    }

    @Test fun embedPageGivesPlayerId() {
        assertEquals("142618", KotoPlayParser.embedId(text("megaplay-embed.html")))
        assertNull(KotoPlayParser.embedId("<html>no player</html>"))
    }

    @Test fun encDecryptsToMasterUrl() {
        val sources = mapper.readValue<KpSources>(text("megaplay-getSources-142618.json"))
        assertEquals(
            "https://fetch.nexabloom.top/anime/82cec96096d4281b7c95cd7e74623496/aa2eba986ccdcbc55b39821d0e2d603d/master.m3u8",
            KotoPlayParser.decodeSourcesEnc(sources.enc!!)
        )
    }

    @Test fun sourcesTracksSeparateCaptionsFromThumbnails() {
        val tracks = mapper.readValue<KpSources>(text("megaplay-getSources-142618.json")).tracks!!
        val captions = tracks.filter { it.kind == "captions" }
        assertEquals(7, captions.size)
        assertEquals("English", captions.first { it.file!!.endsWith("eng-2.vtt") }.label)
        assertEquals(listOf("thumbnails"), tracks.filter { it.kind != "captions" }.map { it.kind })
    }

    @Test fun masterQualityIsTheTallestVariant() {
        assertEquals(1080, KotoPlayParser.topHeight(text("megaplay-master-sub.m3u8")))
        assertNull(KotoPlayParser.topHeight("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nindex.m3u8"))
    }

    @Test fun variantsAreAbsoluteMediaPlaylistsTallestFirst() {
        val master = "https://fetch.nexabloom.top/anime/82ce/aa2e/master.m3u8"
        val sub = KotoPlayParser.variants(text("megaplay-master-sub.m3u8"), master)
        assertEquals(listOf(1080, 720, 480), sub.map { it.height })
        assertEquals("https://fetch.nexabloom.top/anime/82ce/aa2e/index-f1-v1-a1.m3u8", sub.first().url)
        assertEquals("https://fetch.nexabloom.top/anime/82ce/aa2e/index-new-f2.m3u8", sub.last().url)
        // The dub master has two variants, again with CODECS on the first only.
        val dub = KotoPlayParser.variants(text("megaplay-master-dub.m3u8"), master)
        assertEquals(listOf(720, 480), dub.map { it.height })
        assertTrue(KotoPlayParser.variants("#EXTM3U\n#EXT-X-VERSION:3", master).isEmpty())
    }

    @Test fun browseKeepsTheSitesOrderAndPaging() {
        val result = mapper.readValue<KpBrowse>(text("browse-search-naruto.json"))
        val cards = KotoPlayParser.ordered(result)
        assertEquals(result.ids, cards.map { it.id })
        assertEquals(26, cards.size)
        assertFalse(result.has_more!!)
        assertEquals("Naruto", cards.first { it.slug == "naruto-eybxz" }.title)
    }

    @Test fun movieDetailsHaveOneChapterWithBothAudios() {
        val details = mapper.readValue<KpDetails>(text("anime-movie.json"))
        val movie = KotoPlayParser.titleOf(details, "violet-evergarden-the-movie-2020-aohtz")!!
        assertTrue(KotoPlayParser.isMovie(movie.type))
        val chapter = movie.chapters!!.single()
        assertEquals(24164, chapter.id)
        assertEquals(1, chapter.is_sub)
        assertEquals(1, chapter.is_dub)
        // The other cards are related titles, never the title itself.
        val related = KotoPlayParser.relatedOf(details, movie.slug!!)
        assertTrue(related.none { it.slug == movie.slug })
        assertTrue(related.isNotEmpty())
    }

    @Test fun aniListEntryIsPickedByAniListIdThenMalId() {
        // ani_id is the AniList id; the values here are from the site (Black Clover: AniList 97940, MAL 34572).
        val blackClover = KpAnime(id = 1, slug = "black-clover", ani_id = 97940, mal_id = 34572)
        val other = KpAnime(id = 2, slug = "other", ani_id = 20, mal_id = 20)
        // 86 Special Edition has no ani_id on the site, only the MAL id.
        val eightySix = KpAnime(id = 3, slug = "86-special", ani_id = null, mal_id = 49235)
        val cards = listOf(other, blackClover, eightySix)
        assertEquals("black-clover", KotoPlayParser.pickByAniList(cards, 97940, 34572)?.slug)
        assertEquals("black-clover", KotoPlayParser.pickByAniList(cards, 97940, 0)?.slug)
        assertEquals("86-special", KotoPlayParser.pickByAniList(cards, 131586, 49235)?.slug)
        // A MAL id alone must not match a card that has an AniList id of its own, nor an unknown MAL id (0).
        assertNull(KotoPlayParser.pickByAniList(cards, 1, 34572))
        assertNull(KotoPlayParser.pickByAniList(listOf(eightySix), 1, 0))
    }

    @Test fun chapterIsFoundByEpisodeNumber() {
        val chapters = listOf(
            KpChapter(id = 27298, chapter_number = "1"),
            KpChapter(id = 27299, chapter_number = "2"),
            KpChapter(id = null, chapter_number = "3"),
        )
        assertEquals(27299, KotoPlayParser.chapterFor(chapters, 2)?.id)
        assertNull(KotoPlayParser.chapterFor(chapters, 3))
        assertNull(KotoPlayParser.chapterFor(chapters, 9))
        assertNull(KotoPlayParser.chapterFor(emptyList(), 1))
    }

    @Test fun movieTypeIsSpelledTwoWays() {
        assertTrue(KotoPlayParser.isMovie("Movie"))
        assertTrue(KotoPlayParser.isMovie("MOVIE"))
        assertFalse(KotoPlayParser.isMovie("TV"))
        assertTrue(KotoPlayParser.isOva("OVA"))
        assertTrue(KotoPlayParser.isOva("TV Special"))
    }

    @Test fun plainEpisodeNamesAreDropped() {
        assertNull(KotoPlayParser.episodeName("Episode 1180", "1180"))
        assertNull(KotoPlayParser.episodeName("12", "12"))
        assertNull(KotoPlayParser.episodeName("  ", "3"))
        assertEquals("Homecoming", KotoPlayParser.episodeName("Homecoming", "1"))
        assertEquals("Episode 5", KotoPlayParser.episodeName("Episode 5", "4"))
    }

    @Test fun descriptionDropsCreditAndLineEndings() {
        assertEquals(
            "Line one.\n\nLine two.",
            KotoPlayParser.plainText("Line one.\r\n\r\nLine two.\r\n \r\n[Written by MAL Rewrite]")
        )
        assertNull(KotoPlayParser.plainText("  "))
        assertNull(KotoPlayParser.plainText(null))
    }
}
