version = 1

cloudstream {
    authors = listOf("NropVox")
    language = "en"
    description = "Movies and series from NetMirror, including regional and dubbed titles. Direct MP4 qualities and subtitles."
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://netmirror.plus/icon.png"
}

// Catalog parsing and stream signing are shared with OmniStream.
android.sourceSets.getByName("main").kotlin.directories.add("../shared/netmirrorsource")
