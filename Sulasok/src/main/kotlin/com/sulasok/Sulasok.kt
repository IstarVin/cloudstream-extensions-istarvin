package com.sulasok

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf

open class Sulasok : MainAPI() {
    override var mainUrl = "https://sulasokvid.xyz"
    override var name = "Sulasok"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "movies" to "Movies",
        "genre/comedy" to "Comedy",
        "genre/romance" to "Romance",
        "genre/action" to "Action",
        "genre/digitally-restored" to "Digitally Restored"
    )
}