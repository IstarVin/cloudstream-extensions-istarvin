version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Anime from kotoplay.to, subbed and dubbed. HLS up to 1080p from the site's MegaPlay player, with soft subtitles in several languages."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=kotoplay.to&sz=%size%"

    isCrossPlatform = true
}

// The stream chain (and the pure parser/models it needs) is shared with OmniStream.
android.sourceSets.getByName("main").kotlin.directories.add("../shared/kotoplaysource")
